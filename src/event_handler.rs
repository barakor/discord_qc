use crate::{
    config_handler::GithubConfig,
    db_handler::Db,
    discord_utils::{
        interaction_ack, interaction_end, interaction_response, interaction_update_ack,
    },
    events::pubobot::balance_pubobot_queue,
    github_handler::upload_bytes_to_github,
    interactions::{
        command::{
            AdjustCommand, BackupDbCommand, BalanceCommand, BotCommand, DBStatsCommand,
            DivideCommand, HandleResponse, Permission, QueryCommand, RegisterCommand, RenameCommand,
            RenameOtherCommand, RestoreDBBackupCommand, render_invocation,
        },
        component::handle_component,
    },
};
use anyhow::{Context, Result};
use twilight_interactions::command::CreateCommand;
use std::{
    mem,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::sync::RwLock;
use twilight_cache_inmemory::{InMemoryCache, ResourceType};
use twilight_gateway::{Event, EventTypeFlags, Shard, StreamExt as _};
use twilight_http::Client;
use twilight_model::id::{
    Id,
    marker::{GuildMarker, RoleMarker},
};
use twilight_model::{
    application::interaction::{Interaction, InteractionData, application_command::CommandData},
    id::marker::UserMarker,
};
use twilight_util::builder::InteractionResponseDataBuilder;

pub static SHUTDOWN: AtomicBool = AtomicBool::new(false);

/// Same hardcoded bot owner as the Clojure version.
pub const OWNER_ID: u64 = 88533822521507840;

/// Channel that mutating commands are logged to.
pub const LOG_CHANNEL_ID: u64 = 1519062825446670406;

#[derive(Clone)]
pub struct Bot {
    pub http_client: Arc<Client>,
    pub db: Arc<RwLock<Db>>,
    pub db_path: String,
    pub cache: Arc<InMemoryCache>,
    pub github_config: Option<GithubConfig>,
    pub home_server: Id<GuildMarker>,
    pub admin_role: Id<RoleMarker>,
}

impl Bot {
    pub async fn new(
        http_client: Arc<Client>,
        github_config: Option<GithubConfig>,
        db_path: String,
        home_server: Id<GuildMarker>,
        admin_role: Id<RoleMarker>,
    ) -> Self {
        let cache = Arc::new(
            InMemoryCache::builder()
                .resource_types(ResourceType::all())
                .build(),
        );
        let db = crate::db_handler::boot(&db_path, github_config.as_ref()).await;
        tracing::info!(players = db.elos.len(), db_path, "loaded db");

        Self {
            http_client,
            db: Arc::new(RwLock::new(db)),
            db_path,
            cache,
            github_config,
            home_server,
            admin_role,
        }
    }

    /// Flush the current db state to disk. Call after every mutation.
    pub async fn persist_db(&self) -> Result<()> {
        let db = self.db.read().await;
        crate::db_handler::save(&db, &self.db_path).await
    }

    /// Post a best-effort log line to the bot-logs channel after a mutating
    /// command succeeds. Off the response path: failures are logged, never
    /// surfaced to the user.
    fn log_mutation(&self, invocation: &str, user_id: Id<UserMarker>, detail: Option<&str>) {
        let http_client = self.http_client.clone();
        let content = match detail {
            Some(detail) => format!("`{}` run by <@{}> ({})", invocation, user_id, detail),
            None => format!("`{}` run by <@{}>", invocation, user_id),
        };
        tokio::spawn(async move {
            let result = async {
                http_client
                    .create_message(twilight_model::id::Id::new(LOG_CHANNEL_ID))
                    .content(&content)
                    .await
            }
            .await;
            if let Err(e) = result {
                tracing::error!(?e, "failed to post command log");
            }
        });
    }

    /// Fire a best-effort GitHub backup in the background. Off the command
    /// response path: failures are logged, never surfaced to the user. Pushes
    /// are rare (admin-triggered mutations), so SHA conflicts between racing
    /// pushes are tolerated — the next mutation reconciles.
    fn spawn_github_backup(&self) {
        let Some(config) = self.github_config.clone() else {
            return;
        };
        let db = self.db.clone();
        tokio::spawn(async move {
            let bytes = match db.read().await.to_json() {
                Ok(bytes) => bytes,
                Err(e) => {
                    tracing::error!(?e, "failed to serialize db for github backup");
                    return;
                }
            };
            if let Err(e) = upload_bytes_to_github(
                &bytes.into(),
                &config.owner,
                &config.repo,
                &config.path,
                &config.branch,
            )
            .await
            {
                tracing::error!(?e, "background github backup failed");
            }
        });
    }

    /// Function to eat up an event and decide how to handle it
    pub async fn process_event(&self, event: Event) -> Result<()> {
        match event {
            Event::MessageCreate(message) => {
                if let Err(e) = balance_pubobot_queue(self, &message).await {
                    tracing::error!(?e, "failed to handle pubobot message");
                }
            }
            Event::InteractionCreate(interaction) => {
                let mut interaction = interaction.0;
                match mem::take(&mut interaction.data) {
                    Some(InteractionData::ApplicationCommand(data)) => {
                        let _ = self.handle_command(interaction, *data).await;
                    }
                    Some(InteractionData::MessageComponent(data)) => {
                        let _ = self
                            .handle_component_interaction(interaction, data.custom_id)
                            .await;
                    }
                    _ => {
                        tracing::warn!("ignoring unsupported interaction");
                        return Err(anyhow::format_err!("ignoring unsupported interaction"));
                    }
                };
            }
            _ => (),
        };
        Ok(())
    }

    /// Handle a button press. Only the user who created the original message
    /// may interact with its components — everyone else's press is just acked.
    pub async fn handle_component_interaction(
        &self,
        interaction: Interaction,
        custom_id: String,
    ) -> Result<()> {
        interaction_update_ack(&self.http_client, &interaction).await?;

        let original_author = interaction
            .message
            .as_ref()
            .and_then(|message| message.interaction_metadata.as_ref())
            .map(|metadata| metadata.user.id);
        if original_author != interaction.author_id() {
            return Ok(());
        }

        match handle_component(self, &interaction, &custom_id).await {
            Ok(response) => interaction_response(&self.http_client, &interaction, response).await,
            Err(e) => {
                tracing::error!(?e, custom_id, "error handling component");
                Ok(())
            }
        }
    }

    /// Owner passes every check; admins must hold `admin_role` in `home_server`.
    async fn is_authorized(&self, permission: Permission, user_id: Id<UserMarker>) -> bool {
        match permission {
            Permission::App => true,
            Permission::Admin => user_id == OWNER_ID || self.has_admin_role(user_id).await,
            Permission::Owner => user_id == OWNER_ID,
        }
    }

    /// Whether `user_id` holds `admin_role` in `home_server`. Reads the cache
    /// first and falls back to an HTTP member lookup if the member isn't cached.
    async fn has_admin_role(&self, user_id: Id<UserMarker>) -> bool {
        if let Some(member) = self.cache.member(self.home_server, user_id) {
            return member.roles().contains(&self.admin_role);
        }

        match self
            .http_client
            .guild_member(self.home_server, user_id)
            .await
        {
            Ok(response) => match response.model().await {
                Ok(member) => member.roles.contains(&self.admin_role),
                Err(e) => {
                    tracing::error!(?e, ?user_id, "failed to deserialize member for role check");
                    false
                }
            },
            Err(e) => {
                tracing::error!(?e, ?user_id, "failed to fetch member for role check");
                false
            }
        }
    }

    /// Handle a command interaction. The `match` dispatches the wire name to a
    /// monomorphized [`Bot::run`] over the concrete `BotCommand` type — no
    /// runtime command registry. Arms key off each `C::NAME` so they can't drift
    /// from the `#[command(name = ...)]` attribute.
    pub async fn handle_command(
        &self,
        interaction: Interaction,
        data: CommandData,
    ) -> anyhow::Result<()> {
        match data.name.as_str() {
            BalanceCommand::NAME => self.run::<BalanceCommand>(interaction, data).await,
            DivideCommand::NAME => self.run::<DivideCommand>(interaction, data).await,
            RenameCommand::NAME => self.run::<RenameCommand>(interaction, data).await,
            QueryCommand::NAME => self.run::<QueryCommand>(interaction, data).await,
            RenameOtherCommand::NAME => self.run::<RenameOtherCommand>(interaction, data).await,
            RegisterCommand::NAME => self.run::<RegisterCommand>(interaction, data).await,
            AdjustCommand::NAME => self.run::<AdjustCommand>(interaction, data).await,
            DBStatsCommand::NAME => self.run::<DBStatsCommand>(interaction, data).await,
            BackupDbCommand::NAME => self.run::<BackupDbCommand>(interaction, data).await,
            RestoreDBBackupCommand::NAME => {
                self.run::<RestoreDBBackupCommand>(interaction, data).await
            }
            other => {
                interaction_ack(&self.http_client, &interaction, false).await?;
                let response = InteractionResponseDataBuilder::new()
                    .content(format!("Error: unknown command: {}", other))
                    .build();
                interaction_response(&self.http_client, &interaction, response).await
            }
        }
    }

    /// Run one concrete command end to end: ack, authorize, parse, dispatch to
    /// its [`BotCommand::handle`], then persist + back up + log if it mutates.
    async fn run<C: BotCommand>(
        &self,
        interaction: Interaction,
        data: CommandData,
    ) -> anyhow::Result<()> {
        interaction_ack(&self.http_client, &interaction, C::EPHEMERAL).await?;

        let user_id = interaction
            .author_id()
            .ok_or(anyhow::anyhow!("interaction without author"))?;

        if !self.is_authorized(C::PERMISSION, user_id).await {
            let response = InteractionResponseDataBuilder::new()
                .content("You are not authorized to use this command")
                .build();
            return interaction_response(&self.http_client, &interaction, response).await;
        }

        let invocation = render_invocation(&data);
        let response = async {
            let command =
                C::from_interaction(data.into()).context("failed to parse command data")?;
            command.handle(self, &interaction).await
        }
        .await;

        match response {
            Ok(HandleResponse {
                response,
                log_detail,
            }) => {
                if C::MUTATING {
                    if let Err(e) = self.persist_db().await {
                        tracing::error!(?e, "failed to persist db after {}", C::NAME);
                    }
                    self.spawn_github_backup();
                    self.log_mutation(&invocation, user_id, log_detail.as_deref());
                }
                match response {
                    Some(response) => {
                        interaction_response(&self.http_client, &interaction, response).await
                    }
                    None => interaction_end(&self.http_client, &interaction).await,
                }
            }
            Err(e) => {
                tracing::error!(?e, "error handling command");
                let error_response = InteractionResponseDataBuilder::new()
                    .content(format!("Error: {}", e))
                    .build();
                interaction_response(&self.http_client, &interaction, error_response).await
            }
        }
    }
}

/// entry point for the shard to run, the "main" function
pub async fn runner(mut shard: Shard, bot: Arc<Bot>) {
    // Event loop
    while let Some(item) = shard.next_event(EventTypeFlags::all()).await {
        tracing::debug!(?item, shard = ?shard.id(), "Received Event");

        if let Ok(event) = &item {
            let event = event.clone();
            let bot = bot.clone();
            tokio::spawn(async move { bot.cache.update(&event) });
        };

        match item {
            Ok(Event::GatewayClose(_)) if SHUTDOWN.load(Ordering::Relaxed) => break,
            Ok(event) => {
                let bot = bot.clone();
                tokio::spawn(async move { bot.process_event(event).await })
            }
            Err(source) => {
                tracing::error!(?source, "error receiving event");
                continue;
            }
        };
    }
}
