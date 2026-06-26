//! Shared data model for the Quake Champions bot and webapp. Pure: serde only,
//! no Discord/tokio/wasm deps, so it compiles for both the native bot and the
//! WASM frontend.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

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

    /// All modes, in display order (matches the original embed field order).
    pub const ALL: [GameMode; 12] = [
        GameMode::SacrificeTournament,
        GameMode::Sacrifice,
        GameMode::Objective,
        GameMode::Ctf,
        GameMode::Tdm,
        GameMode::Killing,
        GameMode::RankedDuel,
        GameMode::Instagib,
        GameMode::Slipgate,
        GameMode::Duel,
        GameMode::Ffa,
        GameMode::Tdm2v2,
    ];

    /// Human-friendly label for UI.
    pub fn label(&self) -> &'static str {
        match self {
            Self::Killing => "Killing",
            Self::RankedDuel => "Ranked Duel",
            Self::Tdm => "TDM",
            Self::SacrificeTournament => "Sacrifice Tournament",
            Self::Instagib => "Instagib",
            Self::Slipgate => "Slipgate",
            Self::Duel => "Duel",
            Self::Ctf => "CTF",
            Self::Ffa => "FFA",
            Self::Sacrifice => "Sacrifice",
            Self::Objective => "Objective",
            Self::Tdm2v2 => "TDM 2v2",
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

pub type EloMap = BTreeMap<u64, PlayerElo>;

/// Whole bot database: player elos. Persisted as one JSON file, backed up to
/// GitHub. Serialization lives here; the bot owns the file/GitHub I/O so this
/// stays WASM-safe.
///
/// `elos` (keyed by discord id) is the single source of truth. `name_to_id` is a
/// derived index (quake name -> discord id) so lookups by quake name are O(log
/// n) instead of a full scan. It is not persisted; `from_json` rebuilds it.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Db {
    pub elos: EloMap,
    #[serde(skip)]
    name_to_id: BTreeMap<String, u64>,
}

impl Db {
    pub fn from_json(bytes: &[u8]) -> Result<Self, serde_json::Error> {
        let mut db: Self = serde_json::from_slice(bytes)?;
        db.reindex();
        Ok(db)
    }

    pub fn to_json(&self) -> Result<Vec<u8>, serde_json::Error> {
        serde_json::to_vec_pretty(self)
    }

    /// Rebuild the quake-name index from the primary map. Run after loading,
    /// since the index is derived and not serialized. On a name collision the
    /// highest discord id wins (BTreeMap iterates ascending); names are
    /// expected unique in practice.
    pub fn reindex(&mut self) {
        self.name_to_id = self
            .elos
            .iter()
            .map(|(id, elo)| (elo.quake_name.clone(), *id))
            .collect();
    }

    /// Look up a player's elo by quake name via the index.
    pub fn by_quake_name(&self, quake_name: &str) -> Option<&PlayerElo> {
        let id = self.name_to_id.get(quake_name)?;
        self.elos.get(id)
    }

    /// Register (or replace) a player's elo, keeping the name index in sync.
    pub fn register(&mut self, discord_id: u64, elo: PlayerElo) {
        if let Some(old) = self.elos.get(&discord_id) {
            self.name_to_id.remove(&old.quake_name);
        }
        self.name_to_id.insert(elo.quake_name.clone(), discord_id);
        self.elos.insert(discord_id, elo);
    }

    /// Rename a player, updating the name index. Returns the previous name, or
    /// `None` if no player has that discord id.
    pub fn rename(&mut self, discord_id: u64, new_name: String) -> Option<String> {
        let elo = self.elos.get_mut(&discord_id)?;
        let old_name = std::mem::replace(&mut elo.quake_name, new_name.clone());
        self.name_to_id.remove(&old_name);
        self.name_to_id.insert(new_name, discord_id);
        Some(old_name)
    }

    /// Sorted, de-duplicated quake names — autocomplete source for the webapp.
    pub fn quake_names(&self) -> Vec<String> {
        let mut names: Vec<String> = self.elos.values().map(|e| e.quake_name.clone()).collect();
        names.sort();
        names.dedup();
        names
    }

    /// A registered player's elo for a mode, looked up by quake name.
    pub fn score_for(&self, quake_name: &str, mode: GameMode) -> Option<f64> {
        self.by_quake_name(quake_name).map(|e| e.score(mode))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn game_mode_names_round_trip() {
        for mode in GameMode::ALL {
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

    #[test]
    fn quake_names_sorted_and_unique() {
        let mut db = Db::default();
        db.elos.insert(2, PlayerElo::new("zed".into()));
        db.elos.insert(1, PlayerElo::new("ace".into()));
        assert_eq!(db.quake_names(), vec!["ace".to_string(), "zed".to_string()]);
    }

    #[test]
    fn score_for_looks_up_by_name() {
        let mut db = Db::default();
        let mut elo = PlayerElo::new("rapha".into());
        elo.set_score(GameMode::Ctf, 12.0);
        db.register(1, elo);
        assert_eq!(db.score_for("rapha", GameMode::Ctf), Some(12.0));
        assert_eq!(db.score_for("nobody", GameMode::Ctf), None);

        // rename keeps the index consistent: old name misses, new name hits.
        db.rename(1, "rapha2".into());
        assert_eq!(db.score_for("rapha", GameMode::Ctf), None);
        assert_eq!(db.score_for("rapha2", GameMode::Ctf), Some(12.0));
    }
}
