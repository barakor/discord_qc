use crate::{
    balancing::SortMethod,
    db_handler::{Db, GameMode, PlayerElo, player_elo_embed},
    discord_utils::{
        build_components_action_rows, button, str_to_id, text_response, trim_tags,
        user_voice_channel, voice_channel_members,
    },
    event_handler::Bot,
    github_handler::{get_bytes_from_github, upload_bytes_to_github},
    interactions::utils::{divide_hub, named_elos},
};
use anyhow::{Context, Result, anyhow};
use async_trait::async_trait;
use std::{collections::BTreeSet, future::Future, pin::Pin};
use twilight_interactions::command::{CommandModel, CommandOption, CreateCommand, CreateOption};
use twilight_model::{
    application::interaction::{
        Interaction,
        application_command::{CommandData, CommandDataOption, CommandOptionValue},
    },
    channel::message::component::ButtonStyle,
    http::interaction::InteractionResponseData,
    id::{Id, marker::UserMarker},
};
use twilight_util::builder::InteractionResponseDataBuilder;

/// Authorization tier required to invoke a command.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Permission {
    /// Anyone may use it.
    App,
    /// Holders of the admin role in the home server (and the owner).
    Admin,
    /// Bot owner only.
    Owner,
}

/// Future returned by a command's dispatch thunk. Mirrors the boxed future
/// `#[async_trait]` produces for [`BotCommand::handle`].
type HandleFuture<'a> = Pin<Box<dyn Future<Output = Result<HandleResponse>> + Send + 'a>>;

/// Runtime descriptor for one slash command, built from its [`BotCommand`]
/// impl. This replaces the old `commands!` macro + `Command` enum: the trait
/// impls are now the single source of truth, and [`commands`] lists each one
/// once. Adding a command means adding its `BotCommand` impl and one line in
/// [`commands`].
pub struct CommandSpec {
    /// Discord wire name (from `CreateCommand::NAME`).
    pub name: &'static str,
    /// Authorization tier required to run it.
    pub permission: Permission,
    /// Response is only shown to the invoking user.
    pub is_ephemeral: bool,
    /// Mutates the db; success triggers a persist + github backup.
    pub is_mutating: bool,
    /// Build the Discord registration payload.
    pub create: fn() -> twilight_model::application::command::Command,
    /// Dispatch an invocation to the command's handler.
    pub handle: for<'a> fn(CommandData, &'a Bot, &'a Interaction) -> HandleFuture<'a>,
}

impl CommandSpec {
    /// Derive a spec from a `BotCommand` impl. The wire name comes from
    /// `CreateCommand::NAME` via `C::name()`, so registration and dispatch can
    /// never drift from the `#[command(name = ...)]` attribute.
    fn of<C: BotCommand + CreateCommand>() -> Self {
        Self {
            name: C::name(),
            permission: C::permission(),
            is_ephemeral: C::is_ephemeral(),
            is_mutating: C::is_mutating(),
            create: || C::create_command().into(),
            handle: |data, bot, interaction| {
                Box::pin(async move {
                    let command = C::from_interaction(data.clone().into())
                        .context("failed to parse command data")?;
                    command.handle(data, bot, interaction).await
                })
            },
        }
    }
}

/// Every slash command the bot handles — the single registration point used by
/// both Discord command registration and interaction dispatch.
pub fn commands() -> Vec<CommandSpec> {
    vec![
        CommandSpec::of::<BalanceCommand>(),
        CommandSpec::of::<DivideCommand>(),
        CommandSpec::of::<RenameCommand>(),
        CommandSpec::of::<QueryCommand>(),
        CommandSpec::of::<RenameOtherCommand>(),
        CommandSpec::of::<RegisterCommand>(),
        CommandSpec::of::<AdjustCommand>(),
        CommandSpec::of::<DBStatsCommand>(),
        CommandSpec::of::<BackupDbCommand>(),
        CommandSpec::of::<RestoreDBBackupCommand>(),
    ]
}

pub struct HandleResponse {
    /// Optional response to send back to Discord.
    pub response: Option<InteractionResponseData>,
    /// Optional detail for the bot-logs audit line.
    pub log_detail: Option<String>,
}

#[async_trait::async_trait]
pub trait BotCommand: CreateCommand + CommandModel {
    // fn name() -> &'static str;
    fn name() -> &'static str {
        Self::NAME
    }
    fn permission() -> Permission;
    fn is_ephemeral() -> bool;
    fn is_mutating() -> bool;
    /// Handle the command, returning an optional response to send back to Discord.
    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<HandleResponse>;
}

/// Render a slash command invocation back to its textual form, e.g.
/// `/register discord_id:<@123> quake_name:foo score:5`. Used for the bot-logs
/// audit line so the full command (name + every supplied option) is captured.
pub fn render_invocation(data: &CommandData) -> String {
    let mut out = format!("/{}", data.name);
    render_options(&data.options, &mut out);
    out
}

fn render_options(options: &[CommandDataOption], out: &mut String) {
    for option in options {
        match &option.value {
            CommandOptionValue::SubCommand(sub) | CommandOptionValue::SubCommandGroup(sub) => {
                out.push(' ');
                out.push_str(&option.name);
                render_options(sub, out);
            }
            value => {
                out.push_str(&format!(" {}:{}", option.name, render_value(value)));
            }
        }
    }
}

fn render_value(value: &CommandOptionValue) -> String {
    match value {
        CommandOptionValue::String(s) => s.clone(),
        CommandOptionValue::Integer(i) => i.to_string(),
        CommandOptionValue::Number(n) => n.to_string(),
        CommandOptionValue::Boolean(b) => b.to_string(),
        CommandOptionValue::User(id) => format!("<@{}>", id),
        CommandOptionValue::Mentionable(id) => format!("<@{}>", id),
        CommandOptionValue::Channel(id) => format!("<#{}>", id),
        CommandOptionValue::Role(id) => format!("<@&{}>", id),
        CommandOptionValue::Attachment(id) => id.to_string(),
        CommandOptionValue::Focused(s, _) => s.clone(),
        CommandOptionValue::SubCommand(_) | CommandOptionValue::SubCommandGroup(_) => String::new(),
    }
}

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
    #[command(desc = "Discord Tag/ID or Quake Name")]
    pub quaker: String,
}

#[async_trait]
impl BotCommand for QueryCommand {
    fn permission() -> Permission {
        Permission::Admin
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        false
    }

    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let db = bot.db.read().await;
        let command =
            QueryCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let trimmed_quaker = trim_tags(&command.quaker);

        let user = match trimmed_quaker.parse::<u64>() {
            Ok(discord_id) => db.elos.get(&discord_id),
            Err(_) => db.by_quake_name(trimmed_quaker),
        };
        let response = match user {
            Some(user) => player_elo_embed(user),
            None => text_response("couldn't find data for user"),
        };
        Ok(HandleResponse {
            response: Some(response),
            log_detail: None,
        })
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename", desc = "Rename Quaker")]
pub struct RenameCommand {
    #[command(desc = "Quake Name")]
    pub quake_name: String,
}

#[async_trait]
impl BotCommand for RenameCommand {
    fn permission() -> Permission {
        Permission::App
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        _data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let user_id = interaction
            .author_id()
            .map(|id| id.get())
            .ok_or(anyhow!("interaction without author"))?;
        let new_name = self.quake_name;

        let mut db = bot.db.write().await;
        match db.rename(user_id, new_name.clone()) {
            Some(old_name) => Ok(HandleResponse {
                response: Some(
                    InteractionResponseDataBuilder::new()
                        .content(format!("Renamed to `{new_name}`"))
                        .build(),
                ),
                log_detail: Some(format!("{old_name} -> {new_name}")),
            }),
            None => Ok(HandleResponse {
                response: Some(text_response("couldn't find user")),
                log_detail: None,
            }),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "rename-other", desc = "Rename Quaker")]
pub struct RenameOtherCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: Id<UserMarker>,
    #[command(desc = "Quake Name")]
    pub quake_name: String,
}

#[async_trait]
impl BotCommand for RenameOtherCommand {
    fn permission() -> Permission {
        Permission::Admin
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let command = RenameOtherCommand::from_interaction(data.into())
            .context("failed to parse command data")?;

        let discord_id = command.discord_id.get();
        let new_name = command.quake_name;
        let mut db = bot.db.write().await;
        match db.rename(discord_id, new_name.clone()) {
            Some(old_name) => {
                let elo = db.elos.get(&discord_id).expect("just renamed, must exist");
                Ok(HandleResponse {
                    response: Some(player_elo_embed(elo)),
                    log_detail: Some(format!("{old_name} -> {new_name}")),
                })
            }
            None => Ok(HandleResponse {
                response: Some(text_response("couldn't find user")),
                log_detail: None,
            }),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "register", desc = "Register Quaker")]
pub struct RegisterCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: Id<UserMarker>,
    #[command(desc = "Quake Name")]
    pub quake_name: String,
    #[command(desc = "Player's score for all modes")]
    pub score: f64,
}

#[async_trait]
impl BotCommand for RegisterCommand {
    fn permission() -> Permission {
        Permission::Admin
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let command = RegisterCommand::from_interaction(data.into())
            .context("failed to parse command data")?;

        let discord_id = command.discord_id.get();
        let elo = PlayerElo::with_score(command.quake_name, command.score);
        bot.db.write().await.register(discord_id, elo.clone());
        Ok(HandleResponse {
            response: Some(player_elo_embed(&elo)),
            log_detail: None,
        })
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "adjust", desc = "Adjust Quaker Mode Score")]
pub struct AdjustCommand {
    #[command(desc = "Tag a discord user")]
    pub discord_id: Id<UserMarker>,
    #[command(desc = "Player's score for the mode")]
    pub score: f64,
    #[command(desc = "Game Mode (defaults to Sacrifice Tournament)")]
    pub game_mode: Option<GameModeOption>,
}

#[async_trait]
impl BotCommand for AdjustCommand {
    fn permission() -> Permission {
        Permission::Admin
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let command =
            AdjustCommand::from_interaction(data.into()).context("failed to parse command data")?;

        let discord_id = command.discord_id.get();
        let mode = command
            .game_mode
            .unwrap_or(GameModeOption::SacrificeTournament)
            .into();
        let mut db = bot.db.write().await;
        match db.elos.get_mut(&discord_id) {
            Some(elo) => {
                let log_detail = Some(format!("{} -> {}", elo.score(mode), command.score));
                elo.set_score(mode, command.score);
                Ok(HandleResponse {
                    response: Some(player_elo_embed(elo)),
                    log_detail,
                })
            }
            None => Ok(HandleResponse {
                response: Some(text_response("couldn't find user")),
                log_detail: None,
            }),
        }
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "db-stats", desc = "Get stats about the db")]
pub struct DBStatsCommand {}

#[async_trait]
impl BotCommand for DBStatsCommand {
    fn permission() -> Permission {
        Permission::Admin
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        false
    }

    async fn handle(
        self,
        _data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let players_registered = bot.db.read().await.elos.len();
        Ok(HandleResponse {
            response: Some(text_response(format!(
                "# Of players registered in db: {}",
                players_registered
            ))),
            log_detail: None,
        })
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
    #[command(desc = "Game Mode (defaults to Sacrifice Tournament)")]
    pub game_mode: Option<GameModeOption>,
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

#[async_trait]
impl BotCommand for BalanceCommand {
    fn permission() -> Permission {
        Permission::App
    }
    fn is_ephemeral() -> bool {
        false
    }
    fn is_mutating() -> bool {
        false
    }

    /// Post the player-selection message: one toggle button per player found
    /// in the caller's voice channel or tagged manually, plus Select All and
    /// Balance! triggers handled as component interactions.
    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let command = BalanceCommand::from_interaction(data.into())
            .context("failed to parse command data")?;
        let guild_id = interaction
            .guild_id
            .ok_or(anyhow!("balance outside a guild"))?;
        let user_id = interaction
            .author_id()
            .ok_or(anyhow!("interaction without author"))?;
        let game_mode: GameMode = command
            .game_mode
            .unwrap_or(GameModeOption::SacrificeTournament)
            .into();

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

        Ok(HandleResponse {
            response: Some(
                InteractionResponseDataBuilder::new()
                    .content(content_lines.join("\n"))
                    .components(build_components_action_rows(buttons))
                    .build(),
            ),
            log_detail: None,
        })
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "divide", desc = "Divide hub inhabitants to other lobbies")]
pub struct DivideCommand {
    #[command(desc = "Game Mode (defaults to Sacrifice Tournament)")]
    pub game_mode: Option<GameModeOption>,
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

#[async_trait]
impl BotCommand for DivideCommand {
    fn permission() -> Permission {
        Permission::App
    }
    fn is_ephemeral() -> bool {
        false
    }
    fn is_mutating() -> bool {
        false
    }

    async fn handle(
        self,
        data: CommandData,
        bot: &Bot,
        interaction: &Interaction,
    ) -> Result<HandleResponse> {
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
            command
                .game_mode
                .unwrap_or(GameModeOption::SacrificeTournament)
                .into(),
            sort_method,
            &manual_entries,
            &ignored_players,
        )
        .await?;
        Ok(HandleResponse {
            response: Some(response),
            log_detail: None,
        })
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "backup-db", desc = "Backup DB to github")]
pub struct BackupDbCommand {}

#[async_trait]
impl BotCommand for BackupDbCommand {
    fn permission() -> Permission {
        Permission::Owner
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        _data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let github_config = bot
            .github_config
            .as_ref()
            .ok_or(anyhow!("no github config"))?;
        let json = bot.db.read().await.to_json()?;
        upload_bytes_to_github(
            &json.into(),
            &github_config.owner,
            &github_config.repo,
            &github_config.path,
            &github_config.branch,
        )
        .await?;
        Ok(HandleResponse {
            response: Some(text_response(format!(
                "https://raw.githubusercontent.com/{}/{}/{}/{}",
                github_config.owner, github_config.repo, github_config.branch, github_config.path
            ))),
            log_detail: None,
        })
    }
}

#[derive(CommandModel, CreateCommand, Debug)]
#[command(name = "restore-db-backup", desc = "Restore DB from github")]
pub struct RestoreDBBackupCommand {}

#[async_trait]
impl BotCommand for RestoreDBBackupCommand {
    fn permission() -> Permission {
        Permission::Owner
    }
    fn is_ephemeral() -> bool {
        true
    }
    fn is_mutating() -> bool {
        true
    }

    async fn handle(
        self,
        _data: CommandData,
        bot: &Bot,
        _interaction: &Interaction,
    ) -> Result<HandleResponse> {
        let github_config = bot
            .github_config
            .as_ref()
            .ok_or(anyhow!("no github config"))?;
        let bytes = get_bytes_from_github(
            &github_config.owner,
            &github_config.repo,
            &github_config.path,
            &github_config.branch,
        )
        .await?;
        let restored = Db::from_json(&bytes)?;
        *bot.db.write().await = restored;
        Ok(HandleResponse {
            response: Some(text_response("DB restored from backup")),
            log_detail: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::commands;
    use std::collections::HashSet;

    /// Guards the registry now that the macro's compile-time name check is gone:
    /// every command's wire name must be non-empty and unique.
    #[test]
    fn command_names_unique_and_nonempty() {
        let mut seen = HashSet::new();
        for spec in commands() {
            assert!(!spec.name.is_empty(), "command has an empty name");
            assert!(
                seen.insert(spec.name),
                "duplicate command name: {}",
                spec.name
            );
        }
    }
}
