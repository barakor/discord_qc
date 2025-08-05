use crate::{
    balancing::SortMethod,
    db_handler::GameMode,
    discord_utils::{NamedElo, balance_teams_embed, build_components_action_rows},
    event_handler::Bot,
    interactions::utils::{custom_id_type, custom_id_value, divide_hub, ids_from_custom_id},
};
use anyhow::{Result, anyhow, bail};
use twilight_model::{
    application::interaction::Interaction,
    channel::message::{
        Component, Message,
        component::{Button, ButtonStyle},
    },
    http::interaction::InteractionResponseData,
};
use twilight_util::builder::{InteractionResponseDataBuilder, embed::EmbedBuilder};

/// All buttons of a message, flattened out of their action rows.
fn message_buttons(message: &Message) -> Vec<Button> {
    message
        .components
        .iter()
        .filter_map(|component| match component {
            Component::ActionRow(row) => Some(row.components.iter()),
            _ => None,
        })
        .flatten()
        .filter_map(|component| match component {
            Component::Button(b) => Some(b.clone()),
            _ => None,
        })
        .collect()
}

fn buttons_response(message: &Message, buttons: Vec<Button>) -> InteractionResponseData {
    InteractionResponseDataBuilder::new()
        .content(message.content.clone())
        .components(build_components_action_rows(
            buttons.into_iter().map(Component::Button).collect(),
        ))
        .build()
}

/// Flip one player button between selected (primary) and unselected (secondary).
fn toggle_primary_secondary(message: &Message, custom_id: &str) -> InteractionResponseData {
    let buttons = message_buttons(message)
        .into_iter()
        .map(|mut b| {
            if b.custom_id.as_deref() == Some(custom_id) {
                b.style = match b.style {
                    ButtonStyle::Primary => ButtonStyle::Secondary,
                    _ => ButtonStyle::Primary,
                };
            }
            b
        })
        .collect();
    buttons_response(message, buttons)
}

/// Mark every player button as selected.
fn select_all_primary_secondary(message: &Message) -> InteractionResponseData {
    let buttons = message_buttons(message)
        .into_iter()
        .map(|mut b| {
            let is_player_button = b
                .custom_id
                .as_deref()
                .map(|id| custom_id_type(id) == "toggle-primary-secondary")
                .unwrap_or(false);
            if is_player_button {
                b.style = ButtonStyle::Primary;
            }
            b
        })
        .collect();
    buttons_response(message, buttons)
}

/// Balance the players currently selected (primary buttons) for the game mode
/// baked into the custom_id.
async fn balance(bot: &Bot, message: &Message, custom_id: &str) -> Result<InteractionResponseData> {
    let game_mode_name = custom_id
        .split('/')
        .nth(1)
        .ok_or(anyhow!("balance! custom_id without game mode"))?;
    let game_mode = GameMode::from_name(game_mode_name)
        .ok_or(anyhow!("unknown game mode: {}", game_mode_name))?;

    let selected: Vec<(u64, String)> = message_buttons(message)
        .into_iter()
        .filter(|b| b.style == ButtonStyle::Primary)
        .filter_map(|b| {
            let id = b.custom_id?;
            if custom_id_type(&id) != "toggle-primary-secondary" {
                return None;
            }
            Some((custom_id_value(&id).parse().ok()?, b.label?))
        })
        .collect();

    if selected.len() <= 3 {
        let embed = EmbedBuilder::new()
            .color(crate::discord_utils::EMBED_COLOR)
            .title("No enough players")
            .build();
        return Ok(InteractionResponseDataBuilder::new()
            .embeds([embed])
            .build());
    }

    let db = bot.db.read().await;
    let players: Vec<NamedElo> = selected
        .into_iter()
        .map(|(id, label)| match db.elos.get(&id) {
            Some(elo) => NamedElo {
                id,
                name: elo.quake_name.clone(),
                score: elo.score(game_mode),
            },
            None => NamedElo {
                id,
                name: label,
                score: crate::db_handler::DEFAULT_SCORE,
            },
        })
        .collect();

    Ok(InteractionResponseDataBuilder::new()
        .content(message.content.clone())
        .embeds([balance_teams_embed(game_mode, &players)])
        .build())
}

/// Re-run the divide flow with the parameters encoded in the reshuffle button.
async fn reshuffle(
    bot: &Bot,
    interaction: &Interaction,
    custom_id: &str,
) -> Result<InteractionResponseData> {
    let mut sections = custom_id.split('/');
    let game_mode = sections
        .nth(1)
        .and_then(GameMode::from_name)
        .ok_or(anyhow!("reshuffle! custom_id without game mode"))?;
    let sort_method = sections
        .next()
        .and_then(SortMethod::from_name)
        .ok_or(anyhow!("reshuffle! custom_id without sort method"))?;

    let ignored_players = ids_from_custom_id(custom_id, "o");
    let manual_entries = ids_from_custom_id(custom_id, "i");

    let guild_id = interaction
        .guild_id
        .ok_or(anyhow!("reshuffle outside a guild"))?;
    let user_id = interaction
        .author_id()
        .map(|id| id.get())
        .ok_or(anyhow!("interaction without author"))?;

    divide_hub(
        bot,
        guild_id,
        user_id,
        game_mode,
        sort_method,
        &manual_entries,
        &ignored_players,
    )
    .await
}

/// Route a component press by the namespace in its custom_id. Returns the data
/// to overwrite the original message with.
pub async fn handle_component(
    bot: &Bot,
    interaction: &Interaction,
    custom_id: &str,
) -> Result<InteractionResponseData> {
    let message = interaction
        .message
        .as_ref()
        .ok_or(anyhow!("component interaction without message"))?;

    match custom_id_type(custom_id) {
        "toggle-primary-secondary" => Ok(toggle_primary_secondary(message, custom_id)),
        "select-all-primary-secondary" => Ok(select_all_primary_secondary(message)),
        "balance!" => balance(bot, message, custom_id).await,
        "reshuffle!" => reshuffle(bot, interaction, custom_id).await,
        other => bail!("unknown component: {}", other),
    }
}
