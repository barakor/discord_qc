use anyhow::Result;
use twilight_http::Client;
use twilight_model::{
    application::interaction::Interaction,
    http::interaction::{InteractionResponse, InteractionResponseData, InteractionResponseType},
    id::{Id, marker::UserMarker},
};

pub async fn interaction_ack(client: &Client, interaction: &Interaction) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .create_response(
            interaction.id,
            &interaction.token,
            &InteractionResponse {
                kind: InteractionResponseType::DeferredChannelMessageWithSource,
                data: None,
            },
        )
        .await?;
    Ok(())
}

pub async fn interaction_response(
    client: &Client,
    interaction: &Interaction,
    response: InteractionResponseData,
) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .update_response(&interaction.token)
        .content(response.content.as_deref())
        .embeds(response.embeds.as_deref())
        .components(response.components.as_deref())
        .attachments(response.attachments.as_deref().unwrap_or(&[]))
        .await?;

    Ok(())
}

pub async fn interaction_end(client: &Client, interaction: &Interaction) -> Result<()> {
    client
        .interaction(interaction.application_id)
        .delete_response(&interaction.token)
        .await?;
    Ok(())
}

pub fn str_to_id(s: &str) -> Result<u64> {
    s.trim_matches('<')
        .trim_matches('>')
        .trim_matches('@')
        .parse::<u64>()
        .map_err(|_| anyhow::anyhow!("Invalid ID: {}", s))
}
