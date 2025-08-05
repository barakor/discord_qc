use anyhow::{Result, anyhow};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use bytes::Bytes;
use octocrab::Octocrab;
use std::sync::atomic::{AtomicBool, Ordering};

static GITHUB_HANDLER_STARTED: AtomicBool = AtomicBool::new(false);

/// Mark the github handler as started or not.
pub fn set_github_handler_started(started: bool) {
    GITHUB_HANDLER_STARTED.store(started, Ordering::SeqCst);
}

/// Check if the github handler has been started.
pub fn is_github_handler_started() -> bool {
    GITHUB_HANDLER_STARTED.load(Ordering::SeqCst)
}

pub async fn get_bytes_from_github(
    owner: &str,
    repo: &str,
    path_in_repo: &str,
    branch: &str,
) -> Result<Vec<u8>> {
    let octocrab = octocrab::instance();
    let file_response = octocrab
        .repos(owner, repo)
        .get_content()
        .path(path_in_repo)
        .r#ref(branch)
        .send()
        .await?;

    // file_response.items is a Vec<ContentItem>

    match file_response.items.into_iter().next() {
        Some(base64_file_content) => Ok(STANDARD.decode(
            base64_file_content
                .content
                .ok_or(anyhow!("missing content"))?
                .replace('\n', ""),
        )?),
        None => Err(anyhow!("Couldn't get file content")),
    }
}

pub async fn upload_bytes_to_github(
    data: &Bytes,
    owner: &str,
    repo: &str,
    path_in_repo: &str,
    branch: &str,
) -> Result<()> {
    let octocrab = octocrab::instance();

    // Try to get the existing file to obtain its SHA
    let file = octocrab
        .repos(owner, repo)
        .get_content()
        .path(path_in_repo)
        .r#ref(branch)
        .send()
        .await
        .ok();

    let sha = file
        .as_ref()
        .and_then(|content| content.items.first())
        .map(|item| item.sha.clone())
        .ok_or(anyhow!("Failed to get file SHA"))?;

    // Now update or create the file
    octocrab
        .repos(owner, repo)
        .update_file(path_in_repo, "Update rules DB", data, sha)
        .branch(branch)
        .send()
        .await?;

    Ok(())
}

pub async fn start(github_token: &Option<String>) -> Result<()> {
    if !is_github_handler_started() && github_token.is_some() {
        let octocrab_client = match github_token {
            Some(pat) => Octocrab::builder()
                .personal_token(pat.to_string())
                .build()?,
            None => Octocrab::builder().build()?,
        };
        octocrab::initialise(octocrab_client);
        set_github_handler_started(true);
    }

    Ok({})
}
