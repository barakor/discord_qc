use crate::{
    db_handler::GameMode, discord_utils::balance_teams_embed, event_handler::Bot,
    interactions::utils::named_elos,
};
use anyhow::Result;
use std::collections::BTreeSet;
use twilight_model::gateway::payload::incoming::MessageCreate;

/// Pubobot queue names → game modes (same table as the Clojure version).
fn queue_game_mode(queue: &str) -> Option<GameMode> {
    match queue {
        q if q.starts_with("sac") => Some(GameMode::Sacrifice),
        "sac" => Some(GameMode::SacrificeTournament),
        "sac-tourney" => Some(GameMode::SacrificeTournament),
        q if q.starts_with("ctf") => Some(GameMode::Ctf),
        "ctf-tourney" => Some(GameMode::Ctf),
        "ctf" => Some(GameMode::Ctf),
        "tdm" => Some(GameMode::Tdm),
        "slipgate" => Some(GameMode::Slipgate),
        "ca2v2" | "ca4v4" => Some(GameMode::Killing),
        "ffa" => Some(GameMode::Ffa),
        "2v2" => Some(GameMode::Tdm2v2),
        _ => None,
    }
}

/// Watch for pubobot "**queue** has started" embeds and reply with balanced
/// team suggestions for the queued players.
pub async fn balance_pubobot_queue(bot: &Bot, message: &MessageCreate) -> Result<()> {
    let Some(embed) = message.embeds.first() else {
        return Ok(());
    };
    let Some(title) = embed.title.as_deref() else {
        return Ok(());
    };
    if !title.contains("has started") {
        return Ok(());
    }
    tracing::trace!(title, "got a potential pubobot message");

    let Some(guild_id) = message.guild_id else {
        return Ok(());
    };

    // Queue name sits between the first pair of ** markers in the title.
    let Some(game_mode) = title
        .split('*')
        .nth(2)
        .map(str::to_lowercase)
        .and_then(|queue| queue_game_mode(&queue))
    else {
        tracing::warn!(title, "unknown pubobot queue");
        return Ok(());
    };

    // First field value lists the players as mentions: "<@111> <@222> ...".
    let Some(field) = embed.fields.first() else {
        return Ok(());
    };
    let discord_ids: BTreeSet<u64> = field
        .value
        .replace(['<', '@', '>'], "")
        .split_whitespace()
        .filter(|token| token.len() > 1)
        .filter_map(|token| token.parse().ok())
        .collect();
    if discord_ids.is_empty() {
        return Ok(());
    }

    let (players, _) = named_elos(bot, guild_id, game_mode, &discord_ids).await;
    let embed = balance_teams_embed(game_mode, &players);

    bot.http_client
        .create_message(message.channel_id)
        .reply(message.id)
        .embeds(&[embed])
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queue_names_map_to_modes() {
        assert_eq!(queue_game_mode("sac"), Some(GameMode::Sacrifice));
        assert_eq!(queue_game_mode("ca4v4"), Some(GameMode::Killing));
        assert_eq!(queue_game_mode("2v2"), Some(GameMode::Tdm2v2));
        assert_eq!(queue_game_mode("duel"), None);
    }

    #[test]
    fn player_ids_parse_from_mentions() {
        let value = "<@111> <@2222> 1 <@333>";
        let ids: BTreeSet<u64> = value
            .replace(['<', '@', '>'], "")
            .split_whitespace()
            .filter(|token| token.len() > 1)
            .filter_map(|token| token.parse().ok())
            .collect();
        assert_eq!(ids, BTreeSet::from([111, 2222, 333]));
    }
}
