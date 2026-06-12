use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use tokio::fs;
use twilight_model::{
    channel::message::embed::EmbedField, http::interaction::InteractionResponseData,
};
use twilight_util::builder::{InteractionResponseDataBuilder, embed::EmbedBuilder};

pub const DEFAULT_SCORE: f64 = 5.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameMode {
    Killing,
    RankedDuel,
    Tdm,
    SacrificeTournament,
    Instagib,
    Slipgate,
    Duel,
    Ctf,
    Ffa,
    Sacrifice,
    Objective,
    Tdm2v2,
}

impl GameMode {
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "killing" => Some(Self::Killing),
            "ranked-duel" => Some(Self::RankedDuel),
            "tdm" => Some(Self::Tdm),
            "sacrifice-tournament" => Some(Self::SacrificeTournament),
            "instagib" => Some(Self::Instagib),
            "slipgate" => Some(Self::Slipgate),
            "duel" => Some(Self::Duel),
            "ctf" => Some(Self::Ctf),
            "ffa" => Some(Self::Ffa),
            "sacrifice" => Some(Self::Sacrifice),
            "objective" => Some(Self::Objective),
            "tdm-2v2" => Some(Self::Tdm2v2),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            Self::Killing => "killing",
            Self::RankedDuel => "ranked-duel",
            Self::Tdm => "tdm",
            Self::SacrificeTournament => "sacrifice-tournament",
            Self::Instagib => "instagib",
            Self::Slipgate => "slipgate",
            Self::Duel => "duel",
            Self::Ctf => "ctf",
            Self::Ffa => "ffa",
            Self::Sacrifice => "sacrifice",
            Self::Objective => "objective",
            Self::Tdm2v2 => "tdm-2v2",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerElo {
    pub quake_name: String,
    pub killing: f64,
    pub ranked_duel: f64,
    pub tdm: f64,
    pub sacrifice_tournament: f64,
    pub instagib: f64,
    pub slipgate: f64,
    pub duel: f64,
    pub ctf: f64,
    pub ffa: f64,
    pub sacrifice: f64,
    pub objective: f64,
    pub tdm_2v2: f64,
}

impl PlayerElo {
    pub fn new(quake_name: String) -> Self {
        Self::with_score(quake_name, DEFAULT_SCORE)
    }

    pub fn with_score(quake_name: String, score: f64) -> Self {
        Self {
            quake_name,
            killing: score,
            ranked_duel: score,
            tdm: score,
            sacrifice_tournament: score,
            instagib: score,
            slipgate: score,
            duel: score,
            ctf: score,
            ffa: score,
            sacrifice: score,
            objective: score,
            tdm_2v2: score,
        }
    }

    pub fn score(&self, mode: GameMode) -> f64 {
        match mode {
            GameMode::Killing => self.killing,
            GameMode::RankedDuel => self.ranked_duel,
            GameMode::Tdm => self.tdm,
            GameMode::SacrificeTournament => self.sacrifice_tournament,
            GameMode::Instagib => self.instagib,
            GameMode::Slipgate => self.slipgate,
            GameMode::Duel => self.duel,
            GameMode::Ctf => self.ctf,
            GameMode::Ffa => self.ffa,
            GameMode::Sacrifice => self.sacrifice,
            GameMode::Objective => self.objective,
            GameMode::Tdm2v2 => self.tdm_2v2,
        }
    }

    pub fn set_score(&mut self, mode: GameMode, score: f64) {
        match mode {
            GameMode::Killing => self.killing = score,
            GameMode::RankedDuel => self.ranked_duel = score,
            GameMode::Tdm => self.tdm = score,
            GameMode::SacrificeTournament => self.sacrifice_tournament = score,
            GameMode::Instagib => self.instagib = score,
            GameMode::Slipgate => self.slipgate = score,
            GameMode::Duel => self.duel = score,
            GameMode::Ctf => self.ctf = score,
            GameMode::Ffa => self.ffa = score,
            GameMode::Sacrifice => self.sacrifice = score,
            GameMode::Objective => self.objective = score,
            GameMode::Tdm2v2 => self.tdm_2v2 = score,
        }
    }
}

impl From<PlayerElo> for InteractionResponseData {
    fn from(val: PlayerElo) -> Self {
        let embed_fields = vec![
            EmbedField {
                inline: false,
                name: "Sacrifice Tournament".to_string(),
                value: val.sacrifice_tournament.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Sacrifice".to_string(),
                value: val.sacrifice.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Objective".to_string(),
                value: val.objective.to_string(),
            },
            EmbedField {
                inline: false,
                name: "CTF".to_string(),
                value: val.ctf.to_string(),
            },
            EmbedField {
                inline: false,
                name: "TDM".to_string(),
                value: val.tdm.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Killing".to_string(),
                value: val.killing.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Ranked Duel".to_string(),
                value: val.ranked_duel.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Instagib".to_string(),
                value: val.instagib.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Slipgate".to_string(),
                value: val.slipgate.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Duel".to_string(),
                value: val.duel.to_string(),
            },
            EmbedField {
                inline: false,
                name: "FFA".to_string(),
                value: val.ffa.to_string(),
            },
            EmbedField {
                inline: false,
                name: "TDM 2v2".to_string(),
                value: val.tdm_2v2.to_string(),
            },
        ];
        let mut embed = EmbedBuilder::new()
            .color(0x2f3136) // Dark theme color, render a "transparent" background
            .title(format!("{}'s stats", val.quake_name))
            .build();

        embed.fields = embed_fields;
        InteractionResponseDataBuilder::new()
            .embeds([embed])
            .build()
    }
}

pub type EloMap = BTreeMap<u64, PlayerElo>;

/// Whole bot database: player elos plus admin ids. Persisted as one JSON file,
/// backed up to GitHub via the owner commands.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Db {
    pub elos: EloMap,
    #[serde(default)]
    pub admins: BTreeSet<u64>,
}

impl Db {
    pub fn from_json(bytes: &[u8]) -> Result<Self> {
        Ok(serde_json::from_slice(bytes)?)
    }

    pub fn to_json(&self) -> Result<Vec<u8>> {
        Ok(serde_json::to_vec_pretty(self)?)
    }

    /// Load from disk; a missing or unreadable file yields an empty Db.
    pub async fn load(path: &str) -> Self {
        match fs::read(path).await {
            Ok(bytes) => match Self::from_json(&bytes) {
                Ok(db) => db,
                Err(e) => {
                    tracing::error!(?e, path, "failed to parse db file, starting empty");
                    Self::default()
                }
            },
            Err(e) => {
                tracing::warn!(?e, path, "no db file, starting empty");
                Self::default()
            }
        }
    }

    pub async fn save(&self, path: &str) -> Result<()> {
        fs::write(path, self.to_json()?).await?;
        Ok(())
    }
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
        db.admins.insert(7);
        db.save(path).await.unwrap();

        let loaded = Db::load(path).await;
        assert_eq!(loaded.elos.get(&42).unwrap().quake_name, "rapha");
        assert_eq!(loaded.elos.get(&42).unwrap().duel, DEFAULT_SCORE);
        assert!(loaded.admins.contains(&7));

        tokio::fs::remove_file(path).await.unwrap();
    }

    #[tokio::test]
    async fn missing_db_file_loads_empty() {
        let db = Db::load("/nonexistent/discord_qc_db.json").await;
        assert!(db.elos.is_empty());
        assert!(db.admins.is_empty());
    }

    #[test]
    fn game_mode_names_round_trip() {
        for mode in [
            GameMode::Killing,
            GameMode::RankedDuel,
            GameMode::Tdm,
            GameMode::SacrificeTournament,
            GameMode::Instagib,
            GameMode::Slipgate,
            GameMode::Duel,
            GameMode::Ctf,
            GameMode::Ffa,
            GameMode::Sacrifice,
            GameMode::Objective,
            GameMode::Tdm2v2,
        ] {
            assert_eq!(GameMode::from_name(mode.name()), Some(mode));
        }
    }

    #[test]
    fn score_accessors_match_fields() {
        let mut elo = PlayerElo::new("x".to_string());
        elo.set_score(GameMode::Ctf, 9.5);
        assert_eq!(elo.score(GameMode::Ctf), 9.5);
        assert_eq!(elo.ctf, 9.5);
        assert_eq!(elo.score(GameMode::Duel), DEFAULT_SCORE);
    }
}
