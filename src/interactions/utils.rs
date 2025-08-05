use crate::{
    balancing::SortMethod,
    db_handler::{DEFAULT_SCORE, GameMode},
    discord_utils::{
        NamedElo, build_components_action_rows, button, divide_hub_embed, get_user_display_name,
        sibling_voice_channel_names, user_voice_channel, voice_channel_members,
    },
    event_handler::Bot,
};
use anyhow::Result;
use std::collections::{BTreeMap, BTreeSet};
use twilight_model::{
    channel::message::component::ButtonStyle,
    http::interaction::InteractionResponseData,
    id::{
        Id,
        marker::{GuildMarker, UserMarker},
    },
};
use twilight_util::builder::InteractionResponseDataBuilder;

/// Same alphabet as the Clojure version — compresses discord ids so player
/// lists fit in the 100-char custom_id limit.
const BASE_CHARS: &str =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz<.>?;\"'[{]}!@#$%^&*()-_";

pub fn encode_base(mut n: u64) -> String {
    let chars: Vec<char> = BASE_CHARS.chars().collect();
    let base = chars.len() as u64;
    if n == 0 {
        return "0".to_string();
    }
    let mut result = String::new();
    while n > 0 {
        result.insert(0, chars[(n % base) as usize]);
        n /= base;
    }
    result
}

pub fn decode_base(s: &str) -> Option<u64> {
    let base = BASE_CHARS.chars().count() as u64;
    s.chars().try_fold(0u64, |acc, c| {
        let index = BASE_CHARS.chars().position(|b| b == c)? as u64;
        acc.checked_mul(base)?.checked_add(index)
    })
}

/// `tag_ids("o", [1, 2])` → `[":o:1", ":o:2"]` (values base-encoded).
fn tag_ids(tag: &str, ids: &BTreeSet<u64>) -> Vec<String> {
    ids.iter()
        .map(|id| format!(":{}:{}", tag, encode_base(*id)))
        .collect()
}

/// Pull the base-encoded ids tagged `:tag:value` out of a custom_id.
pub fn ids_from_custom_id(custom_id: &str, tag: &str) -> BTreeSet<u64> {
    let prefix = format!(":{}:", tag);
    custom_id
        .split('/')
        .filter_map(|section| section.strip_prefix(&prefix))
        .filter_map(decode_base)
        .collect()
}

pub fn custom_id_type(custom_id: &str) -> &str {
    custom_id.split('/').next().unwrap_or_default()
}

pub fn custom_id_value(custom_id: &str) -> &str {
    custom_id.split('/').next_back().unwrap_or_default()
}

/// Resolve a set of discord ids into named elos for one game mode. Players not
/// in the db get their display name and the default score; their names are
/// also returned separately so callers can warn about them.
pub async fn named_elos(
    bot: &Bot,
    guild_id: Id<GuildMarker>,
    game_mode: GameMode,
    ids: &BTreeSet<u64>,
) -> (Vec<NamedElo>, Vec<String>) {
    let mut players = Vec::with_capacity(ids.len());
    let mut unregistered = Vec::new();
    for id in ids {
        let registered = bot.db.read().await.elos.get(id).cloned();
        match registered {
            Some(elo) => players.push(NamedElo {
                id: *id,
                name: elo.quake_name.clone(),
                score: elo.score(game_mode),
            }),
            None => {
                let name = get_user_display_name(
                    &bot.cache,
                    &bot.http_client,
                    guild_id,
                    Id::<UserMarker>::new(*id),
                )
                .await;
                unregistered.push(name.clone());
                players.push(NamedElo {
                    id: *id,
                    name,
                    score: DEFAULT_SCORE,
                });
            }
        }
    }
    (players, unregistered)
}

/// The divide flow shared by the `/divide` command and the reshuffle button:
/// gather the caller's voice channel plus manual tags, drop spectators, sort,
/// split into lobbies and balance each one.
pub async fn divide_hub(
    bot: &Bot,
    guild_id: Id<GuildMarker>,
    user_id: u64,
    game_mode: GameMode,
    sort_method: SortMethod,
    manual_entries: &BTreeSet<u64>,
    ignored_players: &BTreeSet<u64>,
) -> Result<InteractionResponseData> {
    let voice_channel_id = user_voice_channel(&bot.cache, guild_id, Id::new(user_id));
    let lobby_names = voice_channel_id
        .map(|id| sibling_voice_channel_names(&bot.cache, guild_id, id))
        .unwrap_or_default();
    let members = voice_channel_id
        .map(|id| voice_channel_members(&bot.cache, id))
        .unwrap_or_default();

    let active_players: BTreeSet<u64> = manual_entries
        .iter()
        .chain(members.iter())
        .copied()
        .filter(|id| !ignored_players.contains(id))
        .collect();

    let (mut players, unregistered_names) =
        named_elos(bot, guild_id, game_mode, &active_players).await;
    let (spectators, _) = named_elos(bot, guild_id, game_mode, ignored_players).await;
    let spectator_names: Vec<String> = spectators.into_iter().map(|p| p.name).collect();

    let mut player_pairs: Vec<(u64, f64)> = players.iter().map(|p| (p.id, p.score)).collect();
    sort_method.sort(&mut player_pairs);
    let by_id: BTreeMap<u64, NamedElo> = players.drain(..).map(|p| (p.id, p)).collect();
    let sorted_players: Vec<NamedElo> = player_pairs
        .iter()
        .map(|(id, _)| by_id[id].clone())
        .collect();

    let reshuffle_id = {
        let mut sections = vec![
            "reshuffle!".to_string(),
            game_mode.name().to_string(),
            sort_method.name().to_string(),
        ];
        sections.extend(tag_ids("o", ignored_players));
        sections.extend(tag_ids("i", manual_entries));
        let full = sections.join("/");
        if full.len() < 100 {
            full
        } else {
            format!("reshuffle!/{}/{}", game_mode.name(), sort_method.name())
        }
    };
    let components = build_components_action_rows(vec![
        button(ButtonStyle::Success, reshuffle_id, "Reshuffle!").into(),
    ]);

    let player_count = sorted_players.len();
    let mut content_lines = Vec::new();
    if !unregistered_names.is_empty() {
        content_lines.push(format!(
            "Unregistered Users: {}",
            unregistered_names.join(", ")
        ));
    }
    content_lines.push(format!("Balancing for {}", game_mode.name()));
    content_lines.push(format!("Sorted by {}", sort_method.name()));
    content_lines.push(format!("Found {} players", player_count));
    if player_count <= 11 {
        content_lines.push("Not Enough players to divide into teams".to_string());
    }

    let mut builder = InteractionResponseDataBuilder::new()
        .content(content_lines.join("\n"))
        .components(components);
    if player_count > 11 {
        builder = builder.embeds([divide_hub_embed(
            game_mode,
            &sorted_players,
            &lobby_names,
            &spectator_names,
        )]);
    }
    Ok(builder.build())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_codec_round_trips() {
        for n in [0u64, 1, 84, 85, 12345, 88533822521507840, u64::MAX] {
            assert_eq!(decode_base(&encode_base(n)), Some(n), "n = {}", n);
        }
    }

    #[test]
    fn encoded_id_is_short() {
        // 18-digit discord ids must compress well below the custom_id budget
        assert!(encode_base(88533822521507840).len() <= 10);
    }

    #[test]
    fn tags_round_trip_through_custom_id() {
        let ignored = BTreeSet::from([111u64, 222]);
        let manual = BTreeSet::from([333u64]);
        let mut sections = vec!["reshuffle!".to_string(), "ctf".into(), "random".into()];
        sections.extend(tag_ids("o", &ignored));
        sections.extend(tag_ids("i", &manual));
        let custom_id = sections.join("/");

        assert_eq!(custom_id_type(&custom_id), "reshuffle!");
        assert_eq!(ids_from_custom_id(&custom_id, "o"), ignored);
        assert_eq!(ids_from_custom_id(&custom_id, "i"), manual);
    }

    #[test]
    fn custom_id_helpers_split_on_slash() {
        assert_eq!(
            custom_id_type("toggle-primary-secondary/12345"),
            "toggle-primary-secondary"
        );
        assert_eq!(custom_id_value("toggle-primary-secondary/12345"), "12345");
    }
}
