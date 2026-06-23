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
            AdjustCommand, BackupDbCommand, BalanceCommand, Command, DBStatsCommand, DivideCommand,
            ListAdminsCommand, MakeAdminCommand, Permission, QueryCommand, RegisterCommand,
            RenameCommand, RenameOtherCommand, RestoreDBBackupCommand,
        },
        component::handle_component,
    },
};
use anyhow::Result;
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
use twilight_model::application::interaction::{
    Interaction, InteractionData, application_command::CommandData,
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
}

impl Bot {
    pub async fn new(
        http_client: Arc<Client>,
        github_config: Option<GithubConfig>,
        db_path: String,
    ) -> Self {
        let cache = Arc::new(
            InMemoryCache::builder()
                .resource_types(ResourceType::all())
                .build(),
        );
        let db = crate::db_handler::boot(&db_path, github_config.as_ref()).await;
        tracing::info!(
            players = db.elos.len(),
            admins = db.admins.len(),
            db_path,
            "loaded db"
        );

        Self {
            http_client,
            db: Arc::new(RwLock::new(db)),
            db_path,
            cache,
            github_config,
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
    fn log_mutation(&self, command: Command, user_id: u64) {
        let http_client = self.http_client.clone();
        let content = format!("`/{}` run by <@{}>", command.name(), user_id);
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

    /// Owner passes every check; admins come from the db.
    async fn is_authorized(&self, command: Command, user_id: u64) -> bool {
        match command.permission() {
            Permission::App => true,
            Permission::Admin => {
                user_id == OWNER_ID || self.db.read().await.admins.contains(&user_id)
            }
            Permission::Owner => user_id == OWNER_ID,
        }
    }

    /// Handle a command interaction.
    pub async fn handle_command(
        &self,
        interaction: Interaction,
        data: CommandData,
    ) -> anyhow::Result<()> {
        let Some(command) = Command::from_name(&data.name) else {
            interaction_ack(&self.http_client, &interaction, false).await?;
            let response = InteractionResponseDataBuilder::new()
                .content(format!("Error: unknown command: {}", data.name))
                .build();
            return interaction_response(&self.http_client, &interaction, response).await;
        };
        interaction_ack(&self.http_client, &interaction, command.is_ephemeral()).await?;

        let user_id = interaction
            .author_id()
            .map(|id| id.get())
            .ok_or(anyhow::anyhow!("interaction without author"))?;

        if !self.is_authorized(command, user_id).await {
            let response = InteractionResponseDataBuilder::new()
                .content("You are not authorized to use this command")
                .build();
            return interaction_response(&self.http_client, &interaction, response).await;
        }

        let response = match command {
            Command::Balance => BalanceCommand::handle(data, self, &interaction).await,
            Command::Divide => DivideCommand::handle(data, self, &interaction).await,
            Command::Query => QueryCommand::handle(data, &self.db).await,
            Command::Rename => RenameCommand::handle(data, &self.db, user_id).await,
            Command::RenameOther => RenameOtherCommand::handle(data, &self.db).await,
            Command::Register => RegisterCommand::handle(data, &self.db).await,
            Command::Adjust => AdjustCommand::handle(data, &self.db).await,
            Command::DbStats => DBStatsCommand::handle(&self.db).await,
            Command::MakeAdmin => MakeAdminCommand::handle(data, &self.db).await,
            Command::ListAdmins => ListAdminsCommand::handle(&self.db, &self.http_client).await,
            Command::BackupDb => match &self.github_config {
                Some(config) => BackupDbCommand::handle(&self.db, config).await,
                None => Err(anyhow::anyhow!("no github config")),
            },
            Command::RestoreDbBackup => match &self.github_config {
                Some(config) => RestoreDBBackupCommand::handle(&self.db, config).await,
                None => Err(anyhow::anyhow!("no github config")),
            },
        };

        if response.is_ok() && command.is_mutating() {
            if let Err(e) = self.persist_db().await {
                tracing::error!(?e, "failed to persist db after {}", command.name());
            }
            self.spawn_github_backup();
            self.log_mutation(command, user_id);
        }

        match response {
            Ok(Some(response)) => {
                interaction_response(&self.http_client, &interaction, response).await
            }
            Ok(None) => interaction_end(&self.http_client, &interaction).await,
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
        tracing::info!(?item, shard = ?shard.id(), "Received Event");

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
