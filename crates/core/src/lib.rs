//! Shared data model for the Quake Champions bot and webapp. Pure: serde only,
//! no Discord/tokio/wasm deps, so it compiles for both the native bot and the
//! WASM frontend.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

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

/// Whole bot database: player elos plus admin ids. Persisted as one JSON file,
/// backed up to GitHub. Serialization lives here; the bot owns the file/GitHub
/// I/O so this stays WASM-safe.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Db {
    pub elos: EloMap,
    #[serde(default)]
    pub admins: BTreeSet<u64>,
}

impl Db {
    pub fn from_json(bytes: &[u8]) -> Result<Self, serde_json::Error> {
        serde_json::from_slice(bytes)
    }

    pub fn to_json(&self) -> Result<Vec<u8>, serde_json::Error> {
        serde_json::to_vec_pretty(self)
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
        self.elos
            .values()
            .find(|e| e.quake_name == quake_name)
            .map(|e| e.score(mode))
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
        db.elos.insert(1, elo);
        assert_eq!(db.score_for("rapha", GameMode::Ctf), Some(12.0));
        assert_eq!(db.score_for("nobody", GameMode::Ctf), None);
    }
}
