use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use tokio::{
    fs::File,
    io::{AsyncWriteExt, BufReader},
};
use twilight_model::{
    channel::message::embed::EmbedField, http::interaction::InteractionResponseData,
};
use twilight_util::builder::{InteractionResponseDataBuilder, embed::EmbedBuilder};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerElo {
    pub quake_name: String,
    pub killing: f64,
    pub ranked_duel: f64,
    pub tdm: f64,
    pub sacrifice_tournament: f64,
    pub instagib: f64,
    pub slipgate: f64,
    pub duel: f64,
    pub ctf: f64,
    pub ffa: f64,
    pub sacrifice: f64,
    pub objective: f64,
    pub tdm_2v2: f64,
}

impl Into<InteractionResponseData> for PlayerElo {
    fn into(self) -> InteractionResponseData {
        let embed_fields = vec![
            EmbedField {
                inline: false,
                name: "Sacrifice Tournament".to_string(),
                value: self.sacrifice_tournament.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Sacrifice".to_string(),
                value: self.sacrifice.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Objective".to_string(),
                value: self.objective.to_string(),
            },
            EmbedField {
                inline: false,
                name: "CTF".to_string(),
                value: self.ctf.to_string(),
            },
            EmbedField {
                inline: false,
                name: "TDM".to_string(),
                value: self.tdm.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Killing".to_string(),
                value: self.killing.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Ranked Duel".to_string(),
                value: self.ranked_duel.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Instagib".to_string(),
                value: self.instagib.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Slipgate".to_string(),
                value: self.slipgate.to_string(),
            },
            EmbedField {
                inline: false,
                name: "Duel".to_string(),
                value: self.duel.to_string(),
            },
            EmbedField {
                inline: false,
                name: "FFA".to_string(),
                value: self.ffa.to_string(),
            },
            EmbedField {
                inline: false,
                name: "TDM 2v2".to_string(),
                value: self.tdm_2v2.to_string(),
            },
        ];
        let mut embed = EmbedBuilder::new()
            .color(0x2f3136) // Dark theme color, render a "transparent" background
            .title(format!("{}'s stats", self.quake_name))
            .build();

        embed.fields = embed_fields;
        InteractionResponseDataBuilder::new()
            .embeds([embed])
            .build()
    }
}

pub type EloMap = BTreeMap<u64, PlayerElo>;

async fn write_to_file(map: &EloMap, path: &str) -> Result<()> {
    let json = serde_json::to_string_pretty(map)?;
    let mut file = File::create(path).await?;
    file.write_all(json.as_bytes()).await?;
    Ok(())
}

async fn read_from_file(path: &str) -> Result<EloMap> {
    let file = File::open(path).await?;
    let reader = BufReader::new(file);
    let map = serde_json::from_reader(reader.buffer())?;
    Ok(map)
}
