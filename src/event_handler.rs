use crate::{
    config_handler::GithubConfig,
    db_handler::Db,
    discord_utils::{
        interaction_ack, interaction_end, interaction_response, interaction_update_ack,
    },
    events::pubobot::balance_pubobot_queue,
    interactions::{
        command::{
            AdjustCommand, BackupDbCommand, BalanceCommand, DBStatsCommand, DivideCommand,
            ListAdminsCommand, MakeAdminCommand, QueryCommand, RegisterCommand, RenameCommand,
            RenameOtherCommand, RestoreDBBackupCommand,
        },
        component::handle_component,
    },
};
use anyhow::{Result, bail};
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

const ADMIN_COMMANDS: [&str; 5] = [
    "db-stats",
    "register",
    "adjust",
    "rename-other",
    "list-admins",
];
const OWNER_COMMANDS: [&str; 3] = ["backup-db", "restore-db-backup", "make-admin"];
const MUTATING_COMMANDS: [&str; 6] = [
    "rename",
    "rename-other",
    "register",
    "adjust",
    "make-admin",
    "restore-db-backup",
];

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
        let db = Db::load(&db_path).await;
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
        db.save(&self.db_path).await
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
    async fn is_authorized(&self, command_name: &str, user_id: u64) -> bool {
        if OWNER_COMMANDS.contains(&command_name) {
            user_id == OWNER_ID
        } else if ADMIN_COMMANDS.contains(&command_name) {
            user_id == OWNER_ID || self.db.read().await.admins.contains(&user_id)
        } else {
            true
        }
    }

    /// Handle a command interaction.
    pub async fn handle_command(
        &self,
        interaction: Interaction,
        data: CommandData,
    ) -> anyhow::Result<()> {
        interaction_ack(&self.http_client, &interaction).await?;

        let user_id = interaction
            .author_id()
            .map(|id| id.get())
            .ok_or(anyhow::anyhow!("interaction without author"))?;
        let command_name = data.name.clone();

        if !self.is_authorized(&command_name, user_id).await {
            let response = InteractionResponseDataBuilder::new()
                .content("You are not authorized to use this command")
                .build();
            return interaction_response(&self.http_client, &interaction, response).await;
        }

        let response = match &*command_name {
            "balance" => BalanceCommand::handle(data, self, &interaction).await,
            "divide" => DivideCommand::handle(data, self, &interaction).await,
            "query" => QueryCommand::handle(data, &self.db).await,
            "rename" => RenameCommand::handle(data, &self.db, user_id).await,
            "rename-other" => RenameOtherCommand::handle(data, &self.db).await,
            "register" => RegisterCommand::handle(data, &self.db).await,
            "adjust" => AdjustCommand::handle(data, &self.db).await,
            "db-stats" => DBStatsCommand::handle(&self.db).await,
            "make-admin" => MakeAdminCommand::handle(data, &self.db).await,
            "list-admins" => ListAdminsCommand::handle(&self.db, &self.http_client).await,
            "backup-db" => match &self.github_config {
                Some(config) => BackupDbCommand::handle(&self.db, config).await,
                None => Err(anyhow::anyhow!("no github config")),
            },
            "restore-db-backup" => match &self.github_config {
                Some(config) => RestoreDBBackupCommand::handle(&self.db, config).await,
                None => Err(anyhow::anyhow!("no github config")),
            },
            name => bail!("unknown command: {}", name),
        };

        if response.is_ok()
            && MUTATING_COMMANDS.contains(&&*command_name)
            && let Err(e) = self.persist_db().await
        {
            tracing::error!(?e, "failed to persist db after {}", command_name);
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
