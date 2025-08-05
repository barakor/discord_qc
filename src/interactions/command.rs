use crate::{db_handler::EloMap, discord_utils::str_to_id};
use anyhow::{Context, Result};
use std::sync::Arc;
use tokio::sync::RwLock;
use twilight_interactions::command::{CommandModel, CreateCommand};
use twilight_model::{
    application::interaction::application_command::CommandData,
    http::interaction::InteractionResponseData,
};

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "query", desc = "Query Quake player's stats")]
pub struct QueryCommand {
    #[command(desc = "Tag a registered discord user")]
    pub discord_id: String,
}

impl QueryCommand {
    pub async fn handle(
        data: CommandData,
        elo_map: &Arc<RwLock<EloMap>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command =
            QueryCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        let user = elo_map.read().await.get(&discord_id).cloned();
        match user {
            Some(user) => Ok(Some(user.into())),
            None => Ok(None),
        }
    }
}
