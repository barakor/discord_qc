//! Bot-side db glue: the data model lives in `qc-core` (shared with the
//! webapp); this module adds the Discord embed rendering and the file/GitHub
//! persistence that the WASM frontend must not pull in.

use crate::{config_handler::GithubConfig, github_handler::get_bytes_from_github};
use anyhow::Result;
use std::path::Path;
use tokio::{fs, io::AsyncWriteExt};
use twilight_model::{
    channel::message::embed::EmbedField, http::interaction::InteractionResponseData,
};
use twilight_util::builder::{InteractionResponseDataBuilder, embed::EmbedBuilder};

pub use qc_core::{DEFAULT_SCORE, Db, GameMode, PlayerElo};

/// Render a player's per-mode elos as a stats embed. A free function rather
/// than `From` because both types are foreign to this crate (orphan rule).
pub fn player_elo_embed(elo: &PlayerElo) -> InteractionResponseData {
    let field = |name: &str, value: f64| EmbedField {
        inline: false,
        name: name.to_string(),
        value: value.to_string(),
    };
    let embed_fields = vec![
        field("Sacrifice Tournament", elo.sacrifice_tournament),
        field("Sacrifice", elo.sacrifice),
        field("Objective", elo.objective),
        field("CTF", elo.ctf),
        field("TDM", elo.tdm),
        field("Killing", elo.killing),
        field("Ranked Duel", elo.ranked_duel),
        field("Instagib", elo.instagib),
        field("Slipgate", elo.slipgate),
        field("Duel", elo.duel),
        field("FFA", elo.ffa),
        field("TDM 2v2", elo.tdm_2v2),
    ];
    let mut embed = EmbedBuilder::new()
        .color(0x2f3136) // Dark theme color, render a "transparent" background
        .title(format!("{}'s stats", elo.quake_name))
        .build();
    embed.fields = embed_fields;
    InteractionResponseDataBuilder::new()
        .embeds([embed])
        .build()
}

/// Boot the db, using GitHub as a backstop. Local file is the primary source;
/// if it is missing or corrupt, fall back to the GitHub backup and write it
/// back locally. If both fail, start empty.
pub async fn boot(path: &str, github_config: Option<&GithubConfig>) -> Db {
    let local = fs::read(path).await.ok();

    // Happy path: a valid local file wins, no network needed.
    if let Some(bytes) = &local {
        if let Ok(db) = Db::from_json(bytes) {
            tracing::info!(path, "loaded db from local file");
            return db;
        }
        tracing::error!(path, "local db file is corrupt, trying github backstop");
    } else {
        tracing::warn!(path, "no local db file, trying github backstop");
    }

    let remote = match github_config {
        Some(config) => {
            get_bytes_from_github(&config.owner, &config.repo, &config.path, &config.branch)
                .await
                .map_err(|e| tracing::error!(?e, "failed to fetch github backstop"))
                .ok()
        }
        None => None,
    };

    match decide_load(local.as_deref(), remote.as_deref()) {
        LoadOutcome::Local(db) => db,
        LoadOutcome::Remote(db) => {
            tracing::info!("restored db from github backstop");
            if let Err(e) = save(&db, path).await {
                tracing::error!(?e, path, "failed to write restored db locally");
            }
            db
        }
        LoadOutcome::Empty => {
            tracing::warn!("no usable db source, starting empty");
            Db::default()
        }
    }
}

/// Atomically persist to disk: write a temp file, fsync it, then rename over
/// the target. The rename is atomic on the same filesystem, so a power loss
/// leaves either the old file or the new one — never a truncated mix. The
/// parent directory is fsynced so the rename survives.
pub async fn save(db: &Db, path: &str) -> Result<()> {
    let bytes = db.to_json()?;
    let tmp_path = format!("{path}.tmp");

    let mut file = fs::File::create(&tmp_path).await?;
    file.write_all(&bytes).await?;
    file.sync_all().await?;
    drop(file);

    fs::rename(&tmp_path, path).await?;

    if let Some(parent) = Path::new(path)
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
    {
        let parent = parent.to_path_buf();
        // Opening a directory and fsyncing it is blocking; keep it off the
        // async worker. Best-effort — durability hardening, not correctness.
        let _ = tokio::task::spawn_blocking(move || {
            if let Ok(dir) = std::fs::File::open(&parent) {
                let _ = dir.sync_all();
            }
        })
        .await;
    }
    Ok(())
}

/// Which source `boot` ended up using. Kept separate from the async I/O so the
/// decision is unit-testable without a filesystem or network.
#[derive(Debug)]
pub enum LoadOutcome {
    Local(Db),
    Remote(Db),
    Empty,
}

/// Prefer a parseable local file, then a parseable remote backup, else empty.
pub fn decide_load(local: Option<&[u8]>, remote: Option<&[u8]>) -> LoadOutcome {
    if let Some(bytes) = local
        && let Ok(db) = Db::from_json(bytes)
    {
        return LoadOutcome::Local(db);
    }
    if let Some(bytes) = remote
        && let Ok(db) = Db::from_json(bytes)
    {
        return LoadOutcome::Remote(db);
    }
    LoadOutcome::Empty
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn db_round_trips_through_disk() {
        let path = std::env::temp_dir().join("discord_qc_db_test.json");
        let path = path.to_str().unwrap();

        let mut db = Db::default();
        db.elos.insert(42, PlayerElo::new("rapha".to_string()));
        save(&db, path).await.unwrap();

        let loaded = boot(path, None).await;
        assert_eq!(loaded.elos.get(&42).unwrap().quake_name, "rapha");
        assert_eq!(loaded.elos.get(&42).unwrap().duel, DEFAULT_SCORE);

        tokio::fs::remove_file(path).await.unwrap();
    }

    #[tokio::test]
    async fn db_load_full_db_from_local_file() {
        assert!(Path::new("db.json").exists());
        let db = boot("db.json", None).await;
        assert!(!db.elos.is_empty());
    }

    #[tokio::test]
    async fn missing_db_file_loads_empty() {
        let db = boot("/nonexistent/discord_qc_db.json", None).await;
        assert!(db.elos.is_empty());
    }

    #[tokio::test]
    async fn atomic_save_overwrites_and_leaves_no_tmp() {
        let path = std::env::temp_dir().join("discord_qc_atomic_test.json");
        let path = path.to_str().unwrap();
        let tmp = format!("{path}.tmp");

        let mut first = Db::default();
        first.elos.insert(1, PlayerElo::new("one".to_string()));
        save(&first, path).await.unwrap();

        // Overwrite with different content; the read-back must be the new state.
        let mut second = Db::default();
        second.elos.insert(2, PlayerElo::new("two".to_string()));
        save(&second, path).await.unwrap();

        let loaded = boot(path, None).await;
        assert!(loaded.elos.contains_key(&2));
        assert!(!loaded.elos.contains_key(&1));
        assert!(!Path::new(&tmp).exists(), "temp file must be renamed away");

        tokio::fs::remove_file(path).await.unwrap();
    }

    #[test]
    fn decide_load_prefers_local_then_remote_then_empty() {
        let good = Db::default().to_json().unwrap();
        let corrupt = b"{ not json";

        assert!(matches!(
            decide_load(Some(&good), Some(corrupt)),
            LoadOutcome::Local(_)
        ));
        assert!(matches!(
            decide_load(Some(corrupt), Some(&good)),
            LoadOutcome::Remote(_)
        ));
        assert!(matches!(
            decide_load(None, Some(&good)),
            LoadOutcome::Remote(_)
        ));
        assert!(matches!(
            decide_load(Some(corrupt), Some(corrupt)),
            LoadOutcome::Empty
        ));
        assert!(matches!(decide_load(None, None), LoadOutcome::Empty));
    }
}
