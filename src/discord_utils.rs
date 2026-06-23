use crate::{
    balancing::{PlayersElos, TeamSplit, division_into_lobbies, hybrid_draft_weighted_allocation},
    db_handler::GameMode,
};
use anyhow::Result;
use std::collections::BTreeMap;
use twilight_cache_inmemory::InMemoryCache;
use twilight_http::Client;
use twilight_model::{
    application::interaction::Interaction,
    channel::{
        ChannelType,
        message::{
            Component, Embed,
            component::{ActionRow, Button, ButtonStyle},
            embed::EmbedField,
        },
    },
    http::interaction::{InteractionResponse, InteractionResponseData, InteractionResponseType},
    id::{
        Id,
        marker::{ChannelMarker, GuildMarker, UserMarker},
    },
};
use twilight_util::builder::embed::EmbedBuilder;

pub const EMBED_COLOR: u32 = 9896156;
const TEAMS_DIVIDER: &str = "\n------------------------------VS------------------------------\n";

/// A player ready for team building: display/quake name plus the score for
/// the game mode being balanced.
#[derive(Debug, Clone)]
pub struct NamedElo {
    pub id: u64,
    pub name: String,
    pub score: f64,
}

pub async fn interaction_ack(client: &Client, interaction: &Interaction) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .create_response(
            interaction.id,
            &interaction.token,
            &InteractionResponse {
                kind: InteractionResponseType::DeferredChannelMessageWithSource,
                data: None,
            },
        )
        .await?;
    Ok(())
}

pub async fn interaction_response(
    client: &Client,
    interaction: &Interaction,
    response: InteractionResponseData,
) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .update_response(&interaction.token)
        .content(response.content.as_deref())
        .embeds(response.embeds.as_deref())
        .components(response.components.as_deref())
        .attachments(response.attachments.as_deref().unwrap_or(&[]))
        .await?;

    Ok(())
}

pub async fn interaction_end(client: &Client, interaction: &Interaction) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .delete_response(&interaction.token)
        .await?;
    Ok(())
}

/// Ack a component interaction: defer while keeping the original message.
pub async fn interaction_update_ack(client: &Client, interaction: &Interaction) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .create_response(
            interaction.id,
            &interaction.token,
            &InteractionResponse {
                kind: InteractionResponseType::DeferredUpdateMessage,
                data: None,
            },
        )
        .await?;
    Ok(())
}

/// Pack buttons into action rows of five, the Discord row limit.
pub fn build_components_action_rows(components: Vec<Component>) -> Vec<Component> {
    components
        .chunks(5)
        .map(|chunk| {
            Component::ActionRow(ActionRow {
                components: chunk.to_vec(),
            })
        })
        .collect()
}

pub fn button(
    style: ButtonStyle,
    custom_id: impl Into<String>,
    label: impl Into<String>,
) -> Button {
    Button {
        custom_id: Some(custom_id.into()),
        disabled: false,
        emoji: None,
        label: Some(label.into()),
        style,
        url: None,
        sku_id: None,
    }
}

/// Voice channel the user currently sits in, from the gateway cache.
pub fn user_voice_channel(
    cache: &InMemoryCache,
    guild_id: Id<GuildMarker>,
    user_id: Id<UserMarker>,
) -> Option<Id<ChannelMarker>> {
    cache
        .voice_state(user_id, guild_id)
        .map(|state| state.channel_id())
}

pub fn voice_channel_members(cache: &InMemoryCache, channel_id: Id<ChannelMarker>) -> Vec<u64> {
    cache
        .voice_channel_states(channel_id)
        .map(|states| states.map(|state| state.user_id().get()).collect())
        .unwrap_or_default()
}

/// Names of the voice channels sharing a category with `channel_id`, sorted by
/// position, skipping the hub channel itself (first by position), paired up as
/// (team1 lobby, team2 lobby) — mirrors the Clojure sibling-channel logic.
pub fn sibling_voice_channel_names(
    cache: &InMemoryCache,
    guild_id: Id<GuildMarker>,
    channel_id: Id<ChannelMarker>,
) -> Vec<(String, String)> {
    let parent_id = cache.channel(channel_id).and_then(|c| c.parent_id);

    let Some(channel_ids) = cache.guild_channels(guild_id) else {
        return Vec::new();
    };

    let mut siblings: Vec<(i32, String)> = channel_ids
        .iter()
        .filter_map(|id| cache.channel(*id))
        .filter(|c| c.kind == ChannelType::GuildVoice && c.parent_id == parent_id)
        .filter_map(|c| Some((c.position?, c.name.clone()?)))
        .collect();
    siblings.sort_by_key(|(position, _)| *position);

    siblings
        .into_iter()
        .skip(1) // first one is the HUB channel, used as the gathering lobby
        .map(|(_, name)| name)
        .collect::<Vec<_>>()
        .chunks(2)
        .filter(|pair| pair.len() == 2)
        .map(|pair| (pair[0].clone(), pair[1].clone()))
        .collect()
}

/// Guild nick, falling back to global name, username, then an HTTP lookup.
/// Lowercased like the Clojure version.
pub async fn get_user_display_name(
    cache: &InMemoryCache,
    http: &Client,
    guild_id: Id<GuildMarker>,
    user_id: Id<UserMarker>,
) -> String {
    if let Some(member) = cache.member(guild_id, user_id)
        && let Some(nick) = member.nick()
    {
        return nick.to_lowercase();
    }
    if let Some(user) = cache.user(user_id) {
        return user
            .global_name
            .clone()
            .unwrap_or_else(|| user.name.clone())
            .to_lowercase();
    }
    match http.guild_member(guild_id, user_id).await {
        Ok(response) => match response.model().await {
            Ok(member) => member
                .nick
                .or(member.user.global_name)
                .unwrap_or(member.user.name)
                .to_lowercase(),
            Err(_) => user_id.to_string(),
        },
        Err(_) => user_id.to_string(),
    }
}

fn team_names_by_elo(team: &PlayersElos, names: &BTreeMap<u64, String>) -> String {
    let mut members: Vec<(&u64, &f64)> = team.iter().collect();
    members.sort_by(|a, b| b.1.total_cmp(a.1));
    members
        .iter()
        .map(|(id, _)| names.get(id).cloned().unwrap_or_else(|| id.to_string()))
        .collect::<Vec<_>>()
        .join(", ")
}

fn format_team_option(
    split: &TeamSplit,
    names: &BTreeMap<u64, String>,
    title: String,
) -> EmbedField {
    let team1 = format!(
        "{}", // |  Team ELO: {:.3}",
        team_names_by_elo(&split.team1, names),
        // split.team1_elo_sum
    );
    let team2 = format!(
        "{}", // |  Team ELO: {:.3}",
        team_names_by_elo(&split.team2, names),
        // split.team2_elo_sum
    );
    EmbedField {
        inline: false,
        name: title,
        value: format!("{}{}{}", team1, TEAMS_DIVIDER, team2),
    }
}

/// Top three ELO-weighted team splits plus everyone's scores.
pub fn balance_teams_embed(game_mode: GameMode, players: &[NamedElo]) -> Embed {
    let elos: PlayersElos = players.iter().map(|p| (p.id, p.score)).collect();
    let names: BTreeMap<u64, String> = players.iter().map(|p| (p.id, p.name.clone())).collect();

    let mut fields: Vec<EmbedField> = crate::balancing::weighted_allocation(&elos)
        .iter()
        .take(3)
        .enumerate()
        .map(|(i, split)| {
            format_team_option(
                split,
                &names,
                format!("ELO Weighted  Team Option #{}", i + 1),
            )
        })
        .collect();

    // fields.push(EmbedField {
    //     inline: false,
    //     name: "Players ELOs:".to_string(),
    //     value: players
    //         .iter()
    //         .map(|p| format!("{}: {:.3}", p.name, p.score))
    //         .collect::<Vec<_>>()
    //         .join(", "),
    // });

    let mut embed = EmbedBuilder::new()
        .color(EMBED_COLOR)
        .title("Balance Options")
        .description(format!("Suggested Teams for {}:", game_mode.name()))
        .build();
    embed.fields = fields;
    embed
}

/// Split already-sorted players into 8/6-player lobbies, balance each with the
/// hybrid draft, and render one field per lobby. Players past the lobby sizes
/// (odd one out included) are listed as spectators.
pub fn divide_hub_embed(
    game_mode: GameMode,
    players: &[NamedElo],
    lobby_names: &[(String, String)],
    spectator_names: &[String],
) -> Embed {
    let team_sizes = division_into_lobbies(players.len());

    let mut spectator_names = spectator_names.to_vec();
    let mut fields = Vec::new();
    let mut rest = players;

    for (lobby_index, size) in team_sizes.iter().enumerate() {
        let take = (*size).min(rest.len());
        let (lobby_players, remaining) = rest.split_at(take);
        rest = remaining;
        if lobby_players.is_empty() {
            continue;
        }

        let elos: PlayersElos = lobby_players.iter().map(|p| (p.id, p.score)).collect();
        let names: BTreeMap<u64, String> = lobby_players
            .iter()
            .map(|p| (p.id, p.name.clone()))
            .collect();

        let Some(split) = hybrid_draft_weighted_allocation(&elos).into_iter().next() else {
            continue;
        };

        let (team1_name, team2_name) = lobby_names.get(lobby_index).cloned().unwrap_or((
            format!("Lobby {} Team 1", lobby_index + 1),
            format!("Lobby {} Team 2", lobby_index + 1),
        ));

        let team1 = format!(
            "{}: {}",
            team1_name,
            team_names_by_elo(&split.team1, &names)
        );
        let team2 = format!(
            "{}: {}",
            team2_name,
            team_names_by_elo(&split.team2, &names)
        );
        fields.push(EmbedField {
            inline: false,
            name: format!("{} VS {}", team1_name, team2_name),
            value: format!("{}{}{}", team1, TEAMS_DIVIDER, team2),
        });
    }

    spectator_names.extend(rest.iter().map(|p| p.name.clone()));
    if !spectator_names.is_empty() {
        fields.push(EmbedField {
            inline: false,
            name: "Spectators".to_string(),
            value: spectator_names.join(", "),
        });
    }

    let mut embed = EmbedBuilder::new()
        .color(EMBED_COLOR)
        .title("Balance Options")
        .description(format!("Suggested lobbies teams for {}:", game_mode.name()))
        .build();
    embed.fields = fields;
    embed
}

pub fn text_response(content: impl Into<String>) -> InteractionResponseData {
    twilight_util::builder::InteractionResponseDataBuilder::new()
        .content(content)
        .build()
}

pub fn str_to_id(s: &str) -> Result<u64> {
    s.trim_matches('<')
        .trim_matches('>')
        .trim_matches('@')
        .parse::<u64>()
        .map_err(|_| anyhow::anyhow!("Invalid ID: {}", s))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use twilight_cache_inmemory::ResourceType;
    use twilight_model::{
        channel::Channel,
        gateway::payload::incoming::{ChannelCreate, MemberAdd, VoiceStateUpdate},
        guild::Member,
        voice::VoiceState,
    };

    fn cache() -> InMemoryCache {
        InMemoryCache::builder()
            .resource_types(ResourceType::all())
            .build()
    }

    /// Feed a guild voice channel into the cache.
    fn add_voice_channel(
        cache: &InMemoryCache,
        id: u64,
        guild: u64,
        parent: u64,
        position: i32,
        name: &str,
    ) {
        let channel: Channel = serde_json::from_value(json!({
            "id": id.to_string(),
            "type": 2, // GuildVoice
            "guild_id": guild.to_string(),
            "parent_id": parent.to_string(),
            "position": position,
            "name": name,
        }))
        .expect("valid channel json");
        cache.update(&ChannelCreate(channel));
    }

    /// Put a user into a voice channel.
    fn add_voice_state(cache: &InMemoryCache, guild: u64, channel: u64, user: u64) {
        let state: VoiceState = serde_json::from_value(json!({
            "channel_id": channel.to_string(),
            "guild_id": guild.to_string(),
            "user_id": user.to_string(),
            "session_id": "session",
            "deaf": false,
            "mute": false,
            "self_deaf": false,
            "self_mute": false,
            "self_video": false,
            "suppress": false,
            "request_to_speak_timestamp": null,
        }))
        .expect("valid voice state json");
        cache.update(&VoiceStateUpdate(state));
    }

    fn add_member(
        cache: &InMemoryCache,
        guild: u64,
        user: u64,
        nick: Option<&str>,
        username: &str,
    ) {
        let member: Member = serde_json::from_value(json!({
            "user": { "id": user.to_string(), "username": username, "discriminator": "0001" },
            "nick": nick,
            "roles": [],
            "joined_at": "2020-01-01T00:00:00.000000+00:00",
            "deaf": false,
            "mute": false,
            "flags": 0,
        }))
        .expect("valid member json");
        cache.update(&MemberAdd {
            guild_id: Id::new(guild),
            member,
        });
    }

    #[test]
    fn voice_members_and_user_channel_from_cache() {
        let cache = cache();
        let (guild, hub, other) = (1, 100, 200);
        add_voice_state(&cache, guild, hub, 1001);
        add_voice_state(&cache, guild, hub, 1002);
        add_voice_state(&cache, guild, other, 1003);

        let mut members = voice_channel_members(&cache, Id::new(hub));
        members.sort();
        assert_eq!(members, vec![1001, 1002]);

        assert_eq!(
            user_voice_channel(&cache, Id::new(guild), Id::new(1001)),
            Some(Id::new(hub))
        );
        assert_eq!(
            user_voice_channel(&cache, Id::new(guild), Id::new(1003)),
            Some(Id::new(other))
        );
        // user not in any voice channel
        assert_eq!(
            user_voice_channel(&cache, Id::new(guild), Id::new(9999)),
            None
        );
    }

    #[test]
    fn siblings_skip_hub_and_pair_by_position() {
        let cache = cache();
        let (guild, category) = (1, 50);
        // hub at position 0, then four lobbies; out-of-order positions to prove sorting
        add_voice_channel(&cache, 100, guild, category, 0, "Hub");
        add_voice_channel(&cache, 102, guild, category, 2, "Lobby B");
        add_voice_channel(&cache, 101, guild, category, 1, "Lobby A");
        add_voice_channel(&cache, 104, guild, category, 4, "Lobby D");
        add_voice_channel(&cache, 103, guild, category, 3, "Lobby C");
        // a voice channel in a different category must be excluded
        add_voice_channel(&cache, 200, guild, 99, 0, "Other Category");

        let pairs = sibling_voice_channel_names(&cache, Id::new(guild), Id::new(100));
        assert_eq!(
            pairs,
            vec![
                ("Lobby A".to_string(), "Lobby B".to_string()),
                ("Lobby C".to_string(), "Lobby D".to_string()),
            ]
        );
    }

    fn named(id: u64, name: &str, score: f64) -> NamedElo {
        NamedElo {
            id,
            name: name.to_string(),
            score,
        }
    }

    #[test]
    fn balance_embed_has_three_options_plus_scores() {
        // 8 players → C(7,3)=35 splits, embed shows top 3 + the scores field
        let players: Vec<NamedElo> = (1..=8)
            .map(|i| named(i, &format!("p{i}"), i as f64))
            .collect();
        let embed = balance_teams_embed(GameMode::Ctf, &players);

        assert_eq!(embed.fields.len(), 4);
        assert_eq!(embed.fields[0].name, "ELO Weighted  Team Option #1");
        assert_eq!(embed.fields[2].name, "ELO Weighted  Team Option #3");
        assert_eq!(embed.fields[3].name, "Players ELOs:");
        assert!(embed.description.unwrap().contains("ctf"));
        // each option splits 4 vs 4, divider present
        assert!(embed.fields[0].value.contains("VS"));
    }

    #[test]
    fn divide_embed_lobbies_and_spectators() {
        // 13 players: one lobby of 8 (division_into_lobbies(13)->[8]) wait: 12->[6,6]
        // use 14 → [8,6], plus 1 spectator passed in, plus odd leftover handling
        let players: Vec<NamedElo> = (1..=14)
            .map(|i| named(i, &format!("p{i}"), i as f64))
            .collect();
        let lobby_names = vec![
            ("Alpha".to_string(), "Bravo".to_string()),
            ("Charlie".to_string(), "Delta".to_string()),
        ];
        let embed = divide_hub_embed(
            GameMode::Tdm,
            &players,
            &lobby_names,
            &["spec1".to_string()],
        );

        // 14 → [8, 6] = two lobby fields + one spectators field
        assert_eq!(embed.fields.len(), 3);
        assert_eq!(embed.fields[0].name, "Alpha VS Bravo");
        assert_eq!(embed.fields[1].name, "Charlie VS Delta");
        assert_eq!(embed.fields[2].name, "Spectators");
        assert!(embed.fields[2].value.contains("spec1"));
    }

    #[test]
    fn divide_embed_extra_players_become_spectators() {
        // 15 players → division_into_lobbies drops odd → [8,6]=14 placed, 1 left over
        // the leftover must land in spectators
        let players: Vec<NamedElo> = (1..=15)
            .map(|i| named(i, &format!("p{i}"), i as f64))
            .collect();
        let embed = divide_hub_embed(GameMode::Tdm, &players, &[], &[]);

        let spectators = embed
            .fields
            .iter()
            .find(|f| f.name == "Spectators")
            .expect("leftover player should produce a spectators field");
        assert_eq!(spectators.value.split(", ").count(), 1);
    }

    #[tokio::test]
    async fn display_name_prefers_nick_then_username() {
        let cache = cache();
        let http = Client::new("Bot test".to_string());
        let guild = 1;
        add_member(&cache, guild, 1001, Some("NickName"), "username1");
        add_member(&cache, guild, 1002, None, "UserName2");

        // nick wins, lowercased
        assert_eq!(
            get_user_display_name(&cache, &http, Id::new(guild), Id::new(1001)).await,
            "nickname"
        );
        // no nick → username, lowercased
        assert_eq!(
            get_user_display_name(&cache, &http, Id::new(guild), Id::new(1002)).await,
            "username2"
        );
    }
}
