use anyhow::{Error, Result};

use dotenv::dotenv;
use std::{
    env,
    sync::atomic::{AtomicBool, Ordering},
};
use twilight_model::id::{
    Id,
    marker::{GuildMarker, RoleMarker},
};

static CONFIG_HANDLER_STARTED: AtomicBool = AtomicBool::new(false);

/// Mark the config handler as started or not.
pub fn set_config_handler_started(started: bool) {
    CONFIG_HANDLER_STARTED.store(started, Ordering::SeqCst);
}

pub fn is_config_handler_started() -> bool {
    CONFIG_HANDLER_STARTED.load(Ordering::SeqCst)
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct GithubConfig {
    pub token: Option<String>,
    pub owner: String,
    pub repo: String,
    pub branch: String,
    pub path: String,
}

impl GithubConfig {
    pub fn new() -> Result<Self, Error> {
        start()?;
        Ok(Self {
            token: env::var("GITHUB_TOKEN").ok(),
            owner: env::var("GITHUB_OWNER")?,
            repo: env::var("GITHUB_REPO")?,
            branch: env::var("GITHUB_BRANCH")?,
            path: env::var("GITHUB_PATH")?,
        })
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct EnvConfig {
    pub discord_token: String,
    pub github_config: Option<GithubConfig>,
    pub db_path: String,
    pub home_server: Id<GuildMarker>,
    pub admin_role: Id<RoleMarker>,
}

fn home_server() -> Result<Id<GuildMarker>, Error> {
    let raw = env::var("HOME_SERVER").unwrap_or("1104894380080365710".to_string());
    Ok(Id::new(raw.parse()?))
}

/// Role in `home_server` that grants admin command access.
fn admin_role() -> Result<Id<RoleMarker>, Error> {
    let raw = env::var("ADMIN_ROLE")?;
    Ok(Id::new(raw.parse()?))
}

const DEFAULT_DB_PATH: &str = "db.json";

impl EnvConfig {
    pub fn new() -> Result<Self, Error> {
        start()?;
        Ok(Self {
            discord_token: env::var("DISCORD_TOKEN")?,
            github_config: GithubConfig::new().ok(),
            db_path: env::var("DB_PATH").unwrap_or_else(|_| DEFAULT_DB_PATH.to_string()),
            home_server: home_server()?,
            admin_role: admin_role()?,
        })
    }
}

#[allow(dead_code)]
pub fn get_testing_config() -> Result<EnvConfig, Error> {
    start()?;

    Ok(EnvConfig {
        discord_token: env::var("DISCORD_TESTING_TOKEN")?,
        github_config: GithubConfig::new().ok(),
        db_path: env::var("DB_PATH").unwrap_or_else(|_| DEFAULT_DB_PATH.to_string()),
        home_server: home_server()?,
        admin_role: admin_role()?,
    })
}

pub fn start() -> Result<(), Error> {
    if !is_config_handler_started() {
        dotenv().ok();
        set_config_handler_started(true);
    }
    Ok(())
}
