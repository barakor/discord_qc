use crate::{
    balancing::SortMethod,
    config_handler::GithubConfig,
    db_handler::{Db, GameMode, PlayerElo, player_elo_embed},
    discord_utils::{
        build_components_action_rows, button, str_to_id, text_response, user_voice_channel,
        voice_channel_members,
    },
    event_handler::Bot,
    github_handler::{get_bytes_from_github, upload_bytes_to_github},
    interactions::utils::{divide_hub, named_elos},
};
use anyhow::{Context, Result, anyhow};
use std::{collections::BTreeSet, sync::Arc};
use tokio::sync::RwLock;
use twilight_http::Client;
use twilight_interactions::command::{CommandModel, CommandOption, CreateCommand, CreateOption};
use twilight_model::{
    application::interaction::{Interaction, application_command::CommandData},
    channel::message::component::ButtonStyle,
    http::interaction::InteractionResponseData,
    id::Id,
};
use twilight_util::builder::InteractionResponseDataBuilder;

/// Game modes exposed as slash command choices (mirrors the Clojure choice list).
#[derive(CommandOption, CreateOption, Debug, Clone, Copy)]
pub enum GameModeOption {
    #[option(name = "Sacrifice", value = "sacrifice")]
    Sacrifice,
    #[option(name = "Objective", value = "objective")]
    Objective,
    #[option(name = "Killing", value = "killing")]
    Killing,
    #[option(name = "Sacrifice Tournament", value = "sacrifice-tournament")]
    SacrificeTournament,
    #[option(name = "Slipgate", value = "slipgate")]
    Slipgate,
    #[option(name = "CTF", value = "ctf")]
    Ctf,
    #[option(name = "TDM", value = "tdm")]
    Tdm,
    #[option(name = "TDM 2V2", value = "tdm-2v2")]
    Tdm2v2,
}

impl From<GameModeOption> for GameMode {
    fn from(option: GameModeOption) -> Self {
        match option {
            GameModeOption::Sacrifice => GameMode::Sacrifice,
            GameModeOption::Objective => GameMode::Objective,
            GameModeOption::Killing => GameMode::Killing,
            GameModeOption::SacrificeTournament => GameMode::SacrificeTournament,
            GameModeOption::Slipgate => GameMode::Slipgate,
            GameModeOption::Ctf => GameMode::Ctf,
            GameModeOption::Tdm => GameMode::Tdm,
            GameModeOption::Tdm2v2 => GameMode::Tdm2v2,
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "query", desc = "Query Quake player's stats")]
pub struct QueryCommand {
    #[command(desc = "Tag a registered discord user")]
    pub discord_id: String,
}

impl QueryCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command =
            QueryCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        let user = db.read().await.elos.get(&discord_id).cloned();
        match user {
            Some(user) => Ok(Some(player_elo_embed(&user))),
            None => Ok(Some(text_response("couldn't find data for user"))),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename", desc = "Rename Quaker")]
pub struct RenameCommand {
    #[command(desc = "Quake Name")]
    pub quake_name: String,
}

impl RenameCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
        user_id: u64,
    ) -> Result<Option<InteractionResponseData>> {
        let command =
            RenameCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let mut db = db.write().await;
        match db.elos.get_mut(&user_id) {
            Some(elo) => {
                elo.quake_name = command.quake_name;
                Ok(Some(player_elo_embed(elo)))
            }
            None => Ok(Some(text_response("couldn't find user"))),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename-other", desc = "Rename Quaker")]
pub struct RenameOtherCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: String,
    #[command(desc = "Quake Name")]
    pub quake_name: String,
}

impl RenameOtherCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command = RenameOtherCommand::from_interaction(data.into())
            .context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        let mut db = db.write().await;
        match db.elos.get_mut(&discord_id) {
            Some(elo) => {
                elo.quake_name = command.quake_name;
                Ok(Some(player_elo_embed(elo)))
            }
            None => Ok(Some(text_response("couldn't find user"))),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "register", desc = "Register Quaker")]
pub struct RegisterCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: String,
    #[command(desc = "Quake Name")]
    pub quake_name: String,
    #[command(desc = "Player's score for all modes")]
    pub score: f64,
}

impl RegisterCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command = RegisterCommand::from_interaction(data.into())
            .context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        let elo = PlayerElo::with_score(command.quake_name, command.score);
        db.write().await.elos.insert(discord_id, elo.clone());
        Ok(Some(player_elo_embed(&elo)))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "adjust", desc = "Adjust Quaker Mode Score")]
pub struct AdjustCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: String,
    #[command(desc = "Game Mode")]
    pub game_mode: GameModeOption,
    #[command(desc = "Player's score for the mode")]
    pub score: f64,
}

impl AdjustCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command =
            AdjustCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        let mut db = db.write().await;
        match db.elos.get_mut(&discord_id) {
            Some(elo) => {
                elo.set_score(command.game_mode.into(), command.score);
                Ok(Some(player_elo_embed(elo)))
            }
            None => Ok(Some(text_response("couldn't find user"))),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "db-stats", desc = "Get stats about the db")]
pub struct DBStatsCommand {}

impl DBStatsCommand {
    pub async fn handle(db: &Arc<RwLock<Db>>) -> Result<Option<InteractionResponseData>> {
        let players_registered = db.read().await.elos.len();
        Ok(Some(text_response(format!(
            "# Of players registered in db: {}",
            players_registered
        ))))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "make-admin", desc = "Make User an Admin")]
pub struct MakeAdminCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: String,
}

impl MakeAdminCommand {
    pub async fn handle(
        data: CommandData,
        db: &Arc<RwLock<Db>>,
    ) -> Result<Option<InteractionResponseData>> {
        let command = MakeAdminCommand::from_interaction(data.into())
            .context("failed to parse command data")?;

        let discord_id = str_to_id(&command.discord_id)?;
        db.write().await.admins.insert(discord_id);
        Ok(Some(text_response(format!(
            "User <@{}> is now an admin",
            discord_id
        ))))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "list-admins", desc = "List all Admins")]
pub struct ListAdminsCommand {}

impl ListAdminsCommand {
    pub async fn handle(
        db: &Arc<RwLock<Db>>,
        http_client: &Client,
    ) -> Result<Option<InteractionResponseData>> {
        let admin_ids: Vec<u64> = db.read().await.admins.iter().copied().collect();
        let mut admin_names = Vec::with_capacity(admin_ids.len());
        for admin_id in admin_ids {
            let user = http_client.user(Id::new(admin_id)).await?.model().await?;
            admin_names.push(user.global_name.unwrap_or(user.name));
        }
        Ok(Some(text_response(admin_names.join(", "))))
    }
}

/// Sort orders exposed on /divide.
#[derive(CommandOption, CreateOption, Debug, Clone, Copy)]
pub enum SortMethodOption {
    #[option(name = "Random", value = "random")]
    Random,
    #[option(name = "Player's Score", value = "score")]
    Score,
}

impl From<SortMethodOption> for SortMethod {
    fn from(option: SortMethodOption) -> Self {
        match option {
            SortMethodOption::Random => SortMethod::Random,
            SortMethodOption::Score => SortMethod::Score,
        }
    }
}

/// Parse the optional player/spectator tag options into a set of ids,
/// silently skipping anything that isn't a user mention.
fn tag_ids(tags: [&Option<String>; 8]) -> BTreeSet<u64> {
    tags.iter()
        .filter_map(|tag| tag.as_deref())
        .filter_map(|tag| str_to_id(tag).ok())
        .collect()
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "balance", desc = "Balance a Quake Champions lobby")]
pub struct BalanceCommand {
    #[command(desc = "Game Mode")]
    pub game_mode: GameModeOption,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag1: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag2: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag3: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag4: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag5: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag6: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag7: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag8: Option<String>,
}

impl BalanceCommand {
    /// Post the player-selection message: one toggle button per player found
    /// in the caller's voice channel or tagged manually, plus Select All and
    /// Balance! triggers handled as component interactions.
    pub async fn handle(
        data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<Option<InteractionResponseData>> {
        let command = BalanceCommand::from_interaction(data.into())
            .context("failed to parse command data")?;
        let guild_id = interaction
            .guild_id
            .ok_or(anyhow!("balance outside a guild"))?;
        let user_id = interaction
            .author_id()
            .ok_or(anyhow!("interaction without author"))?;
        let game_mode: GameMode = command.game_mode.into();

        let manual_entries = tag_ids([
            &command.player_tag1,
            &command.player_tag2,
            &command.player_tag3,
            &command.player_tag4,
            &command.player_tag5,
            &command.player_tag6,
            &command.player_tag7,
            &command.player_tag8,
        ]);

        let members = user_voice_channel(&bot.cache, guild_id, user_id)
            .map(|channel_id| voice_channel_members(&bot.cache, channel_id))
            .unwrap_or_default();

        let found_players: BTreeSet<u64> = manual_entries
            .iter()
            .chain(members.iter())
            .copied()
            .collect();

        let (players, unregistered_names) =
            named_elos(bot, guild_id, game_mode, &found_players).await;

        let mut buttons: Vec<_> = players
            .iter()
            .map(|player| {
                button(
                    ButtonStyle::Secondary,
                    format!("toggle-primary-secondary/{}", player.id),
                    player.name.clone(),
                )
                .into()
            })
            .collect();
        buttons.push(
            button(
                ButtonStyle::Danger,
                "select-all-primary-secondary",
                "Select All",
            )
            .into(),
        );
        buttons.push(
            button(
                ButtonStyle::Success,
                format!("balance!/{}", game_mode.name()),
                "Balance!",
            )
            .into(),
        );

        let mut content_lines = Vec::new();
        if !unregistered_names.is_empty() {
            content_lines.push(format!(
                "Unregistered Users: {}",
                unregistered_names.join(", ")
            ));
        }
        content_lines.push(format!("Balancing for {}", game_mode.name()));

        Ok(Some(
            InteractionResponseDataBuilder::new()
                .content(content_lines.join("\n"))
                .components(build_components_action_rows(buttons))
                .build(),
        ))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "divide", desc = "Divide hub inhabitants to other lobbies")]
pub struct DivideCommand {
    #[command(desc = "Game Mode")]
    pub game_mode: GameModeOption,
    #[command(desc = "Sort players before dividing them")]
    pub sort_by: Option<SortMethodOption>,
    #[command(desc = "Manually tag discord user as a spectator")]
    pub spectator_tag1: Option<String>,
    #[command(desc = "Manually tag discord user as a spectator")]
    pub spectator_tag2: Option<String>,
    #[command(desc = "Manually tag discord user as a spectator")]
    pub spectator_tag3: Option<String>,
    #[command(desc = "Manually tag discord user as a spectator")]
    pub spectator_tag4: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag1: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag2: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag3: Option<String>,
    #[command(desc = "Manually add tagged discord user to lobby")]
    pub player_tag4: Option<String>,
}

impl DivideCommand {
    pub async fn handle(
        data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<Option<InteractionResponseData>> {
        let command =
            DivideCommand::from_interaction(data.into()).context("failed to parse command data")?;
        let guild_id = interaction
            .guild_id
            .ok_or(anyhow!("divide outside a guild"))?;
        let user_id = interaction
            .author_id()
            .map(|id| id.get())
            .ok_or(anyhow!("interaction without author"))?;

        let sort_method: SortMethod = command.sort_by.unwrap_or(SortMethodOption::Random).into();
        let ignored_players = tag_ids([
            &command.spectator_tag1,
            &command.spectator_tag2,
            &command.spectator_tag3,
            &command.spectator_tag4,
            &None,
            &None,
            &None,
            &None,
        ]);
        let manual_entries = tag_ids([
            &command.player_tag1,
            &command.player_tag2,
            &command.player_tag3,
            &command.player_tag4,
            &None,
            &None,
            &None,
            &None,
        ]);

        let response = divide_hub(
            bot,
            guild_id,
            user_id,
            command.game_mode.into(),
            sort_method,
            &manual_entries,
            &ignored_players,
        )
        .await?;
        Ok(Some(response))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "backup-db", desc = "Backup DB to github")]
pub struct BackupDbCommand {}

impl BackupDbCommand {
    pub async fn handle(
        db: &Arc<RwLock<Db>>,
        github_config: &GithubConfig,
    ) -> Result<Option<InteractionResponseData>> {
        let json = db.read().await.to_json()?;
        upload_bytes_to_github(
            &json.into(),
            &github_config.owner,
            &github_config.repo,
            &github_config.path,
            &github_config.branch,
        )
        .await?;
        Ok(Some(text_response(format!(
            "https://raw.githubusercontent.com/{}/{}/{}/{}",
            github_config.owner, github_config.repo, github_config.branch, github_config.path
        ))))
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "restore-db-backup", desc = "Restore DB from github")]
pub struct RestoreDBBackupCommand {}

impl RestoreDBBackupCommand {
    pub async fn handle(
        db: &Arc<RwLock<Db>>,
        github_config: &GithubConfig,
    ) -> Result<Option<InteractionResponseData>> {
        let bytes = get_bytes_from_github(
            &github_config.owner,
            &github_config.repo,
            &github_config.path,
            &github_config.branch,
        )
        .await?;
        let restored = Db::from_json(&bytes)?;
        *db.write().await = restored;
        Ok(Some(text_response("DB restored from backup")))
    }
}
