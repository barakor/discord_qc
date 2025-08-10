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

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename", desc = "TODO")]
pub struct RenameCommand {
    #[command(desc = "Rename yourself")]
    quake_name: String,
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "balance", desc = "TODO")]
pub struct BalanceCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "divide", desc = "TODO")]
pub struct DivideCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "register", desc = "TODO")]
pub struct RegisterCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename-other", desc = "TODO")]
pub struct RenameOtherCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "adjust", desc = "TODO")]
pub struct AdjustCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "db-stats", desc = "TODO")]
pub struct DBStatsCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "make-admin", desc = "TODO")]
pub struct MakeAdminCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "list-admins", desc = "TODO")]
pub struct ListAdminsCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "backup-db", desc = "TODO")]
pub struct BackupDbCommand {}
#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "restore-db-backup", desc = "TODO")]
pub struct RestoreDBBackupCommand {}
