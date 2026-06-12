use itertools::Itertools;
use rand::seq::SliceRandom;
use std::collections::BTreeMap;

/// Player elos for a single game mode, keyed by discord id.
pub type PlayersElos = BTreeMap<u64, f64>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortMethod {
    Random,
    Score,
}

impl SortMethod {
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "random" => Some(Self::Random),
            "score" => Some(Self::Score),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            Self::Random => "random",
            Self::Score => "score",
        }
    }

    /// Orders players before dividing them into lobbies.
    pub fn sort(&self, players: &mut [(u64, f64)]) {
        match self {
            Self::Random => players.shuffle(&mut rand::rng()),
            Self::Score => players.sort_by(|a, b| a.1.total_cmp(&b.1)),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct TeamSplit {
    pub team1: PlayersElos,
    pub team1_elo_sum: f64,
    pub team2: PlayersElos,
    pub team2_elo_sum: f64,
    pub distance_from_ideal: f64,
    pub deviation_from_ideal: f64,
}

fn team_elo(team: &PlayersElos) -> f64 {
    team.values().sum()
}

/// Half the total elo pool — what each team would hold in a perfect split.
fn ideal_team_elo(players_elos: &PlayersElos) -> f64 {
    team_elo(players_elos) / 2.0
}

fn nth_highest_elo_player(players_elos: &PlayersElos, rank: usize) -> Option<u64> {
    players_elos
        .iter()
        .sorted_by(|a, b| b.1.total_cmp(a.1))
        .nth(rank)
        .map(|(id, _)| *id)
}

fn complementary_team(all_players: &PlayersElos, team1: &PlayersElos) -> PlayersElos {
    all_players
        .iter()
        .filter(|(id, _)| !team1.contains_key(id))
        .map(|(id, elo)| (*id, *elo))
        .collect()
}

fn deviation_from_ideal(ideal: f64, team: &PlayersElos) -> f64 {
    (ideal - team_elo(team)).abs() / ideal
}

fn split_into_teams(players_elos: &PlayersElos, team1: PlayersElos) -> TeamSplit {
    let ideal = ideal_team_elo(players_elos);
    let team2 = complementary_team(players_elos, &team1);
    let team1_elo_sum = team_elo(&team1);
    let team2_elo_sum = team_elo(&team2);
    let distance_from_ideal = (ideal - team1_elo_sum).abs();
    TeamSplit {
        deviation_from_ideal: distance_from_ideal / ideal,
        team1,
        team1_elo_sum,
        team2,
        team2_elo_sum,
        distance_from_ideal,
    }
}

/// All splits where team1 contains `fixed` and excludes `excluded`, best balanced first.
/// Fixing one player in team1 also dedupes mirror-image splits.
fn constrained_allocations(
    players_elos: &PlayersElos,
    fixed: u64,
    excluded: Option<u64>,
) -> Vec<TeamSplit> {
    let team_size = players_elos.len() / 2;
    if team_size == 0 {
        return Vec::new();
    }
    let ideal = ideal_team_elo(players_elos);
    let rest: Vec<u64> = players_elos
        .keys()
        .copied()
        .filter(|id| *id != fixed && Some(*id) != excluded)
        .collect();

    rest.into_iter()
        .combinations(team_size - 1)
        .map(|mut ids| {
            ids.push(fixed);
            ids.into_iter()
                .map(|id| (id, players_elos[&id]))
                .collect::<PlayersElos>()
        })
        .sorted_by(|a, b| deviation_from_ideal(ideal, a).total_cmp(&deviation_from_ideal(ideal, b)))
        .map(|team1| split_into_teams(players_elos, team1))
        .collect()
}

/// Every possible split with the highest-elo player anchored to team1,
/// ordered by how close the split is to ideal.
pub fn weighted_allocation(players_elos: &PlayersElos) -> Vec<TeamSplit> {
    match nth_highest_elo_player(players_elos, 0) {
        Some(highest) => constrained_allocations(players_elos, highest, None),
        None => Vec::new(),
    }
}

/// Like `weighted_allocation`, but the two highest-elo players (captains)
/// are forced onto opposing teams.
pub fn hybrid_draft_weighted_allocation(players_elos: &PlayersElos) -> Vec<TeamSplit> {
    let captain1 = nth_highest_elo_player(players_elos, 0);
    let captain2 = nth_highest_elo_player(players_elos, 1);
    match (captain1, captain2) {
        (Some(c1), Some(c2)) => constrained_allocations(players_elos, c1, Some(c2)),
        _ => Vec::new(),
    }
}

/// Random split into two equal teams. Unused, like in the Clojure version —
/// kept around in case the random option returns to the balance embed.
#[allow(dead_code)]
pub fn shuffle_list(players_elos: &PlayersElos) -> TeamSplit {
    let team_size = players_elos.len() / 2;
    let mut ids: Vec<u64> = players_elos.keys().copied().collect();
    ids.shuffle(&mut rand::rng());
    let team1 = ids
        .into_iter()
        .take(team_size)
        .map(|id| (id, players_elos[&id]))
        .collect();
    split_into_teams(players_elos, team1)
}

/// Sort by elo ascending, pair players off, give the stronger of each pair to
/// team1. Unused, like in the Clojure version — kept for the same reason.
#[allow(dead_code)]
pub fn draft_allocation(players_elos: &PlayersElos) -> TeamSplit {
    let team1 = players_elos
        .iter()
        .sorted_by(|a, b| a.1.total_cmp(b.1))
        .tuples()
        .map(|(_, stronger)| (*stronger.0, *stronger.1))
        .collect();
    split_into_teams(players_elos, team1)
}

/// Lobby sizes (8s then 6s) covering `number_of_players`, odd leftover dropped.
///
/// For even N: N%8 is even, so
///   N%8=0 → a=N/8 eights, b=0 sixes
///   N%8=2 → a=N/8-2,      b=3
///   N%8=4 → a=N/8-1,      b=2
///   N%8=6 → a=N/8,        b=1
pub fn division_into_lobbies(number_of_players: usize) -> Vec<usize> {
    let n = number_of_players - number_of_players % 2;
    let n_mod_8 = n % 8;
    let n_div_8 = n / 8;
    let (a, b) = match n_mod_8 {
        0 => (n_div_8, 0),
        2 => (n_div_8.saturating_sub(2), 3),
        4 => (n_div_8.saturating_sub(1), 2),
        _ => (n_div_8, 1),
    };
    std::iter::repeat_n(8, a)
        .chain(std::iter::repeat_n(6, b))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Mock elos from the original Clojure namespace, keyed by fake discord ids.
    fn mock_players() -> PlayersElos {
        BTreeMap::from([
            (1, 5.9680834),  // iikxii
            (2, 3.723866),   // cashedcheck
            (3, 8.2246895),  // cubertt
            (4, 13.569449),  // bargleloco
            (5, 7.8625717),  // bamb1
            (6, 4.702424),   // lezyes
            (7, 6.7005982),  // xtortion
            (8, 0.44074476), // rapha
        ])
    }

    #[test]
    fn ideal_team_elo_halves_total() {
        let players = BTreeMap::from([(1, 1.0), (2, 2.0), (3, 3.0), (4, 4.0), (5, 5.0), (6, 6.0)]);
        assert_eq!(ideal_team_elo(&players), 10.5);
    }

    #[test]
    fn nth_highest_ranks_descending() {
        let players = mock_players();
        assert_eq!(nth_highest_elo_player(&players, 0), Some(4));
        assert_eq!(nth_highest_elo_player(&players, 1), Some(3));
        assert_eq!(nth_highest_elo_player(&players, 7), Some(8));
        assert_eq!(nth_highest_elo_player(&players, 8), None);
    }

    #[test]
    fn weighted_allocation_is_sorted_and_complete() {
        let players = mock_players();
        let splits = weighted_allocation(&players);
        // highest player fixed in team1: C(7,3) = 35 splits
        assert_eq!(splits.len(), 35);
        for split in &splits {
            assert_eq!(split.team1.len(), 4);
            assert_eq!(split.team2.len(), 4);
            assert!(split.team1.contains_key(&4));
            // team1 + team2 == all players
            let mut all: Vec<u64> = split
                .team1
                .keys()
                .chain(split.team2.keys())
                .copied()
                .collect();
            all.sort();
            assert_eq!(all, vec![1, 2, 3, 4, 5, 6, 7, 8]);
        }
        // ordered best-first
        for pair in splits.windows(2) {
            assert!(pair[0].deviation_from_ideal <= pair[1].deviation_from_ideal);
        }
    }

    #[test]
    fn hybrid_draft_separates_captains() {
        let players = mock_players();
        let splits = hybrid_draft_weighted_allocation(&players);
        // captain1 fixed, captain2 excluded: C(6,3) = 20 splits
        assert_eq!(splits.len(), 20);
        for split in &splits {
            assert!(split.team1.contains_key(&4));
            assert!(split.team2.contains_key(&3));
        }
    }

    #[test]
    fn shuffle_list_splits_evenly() {
        let players = mock_players();
        let split = shuffle_list(&players);
        assert_eq!(split.team1.len(), 4);
        assert_eq!(split.team2.len(), 4);
    }

    #[test]
    fn draft_allocation_takes_stronger_of_each_pair() {
        let players = BTreeMap::from([(1, 1.0), (2, 2.0), (3, 3.0), (4, 4.0)]);
        let split = draft_allocation(&players);
        // pairs ascending: (1,2) (3,4) → team1 gets 2 and 4
        assert_eq!(split.team1.keys().copied().collect::<Vec<_>>(), vec![2, 4]);
        assert_eq!(split.team2.keys().copied().collect::<Vec<_>>(), vec![1, 3]);
    }

    #[test]
    fn draft_allocation_odd_leftover_goes_to_team2() {
        let players = BTreeMap::from([(1, 1.0), (2, 2.0), (3, 3.0), (4, 4.0), (5, 5.0)]);
        let split = draft_allocation(&players);
        // pairs ascending: (1,2) (3,4), 5 left over → complement team
        assert_eq!(split.team1.keys().copied().collect::<Vec<_>>(), vec![2, 4]);
        assert_eq!(
            split.team2.keys().copied().collect::<Vec<_>>(),
            vec![1, 3, 5]
        );
    }

    #[test]
    fn split_metrics_are_consistent() {
        let players = mock_players();
        let split = weighted_allocation(&players).into_iter().next().unwrap();
        let total: f64 = players.values().sum();
        let ideal = total / 2.0;
        assert!((split.team1_elo_sum + split.team2_elo_sum - total).abs() < 1e-9);
        assert!((split.distance_from_ideal - (ideal - split.team1_elo_sum).abs()).abs() < 1e-9);
        assert!((split.deviation_from_ideal - split.distance_from_ideal / ideal).abs() < 1e-9);
    }

    #[test]
    fn lobby_division_covers_even_counts() {
        assert_eq!(division_into_lobbies(6), vec![6]);
        assert_eq!(division_into_lobbies(8), vec![8]);
        assert_eq!(division_into_lobbies(12), vec![6, 6]);
        assert_eq!(division_into_lobbies(14), vec![8, 6]);
        assert_eq!(division_into_lobbies(16), vec![8, 8]);
        assert_eq!(division_into_lobbies(18), vec![6, 6, 6]);
        assert_eq!(division_into_lobbies(20), vec![8, 6, 6]);
        assert_eq!(division_into_lobbies(22), vec![8, 8, 6]);
        assert_eq!(division_into_lobbies(24), vec![8, 8, 8]);
        // odd leftover dropped
        assert_eq!(division_into_lobbies(15), vec![8, 6]);
    }

    #[test]
    fn sort_method_score_orders_ascending() {
        let mut players = vec![(1, 5.0), (2, 1.0), (3, 3.0)];
        SortMethod::Score.sort(&mut players);
        assert_eq!(players, vec![(2, 1.0), (3, 3.0), (1, 5.0)]);
    }
}
