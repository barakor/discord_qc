//! Quake Champions score calculator — a static Leptos SPA. Fetches the same
//! `db.json` the bot pushes to GitHub and computes per-player score factors
//! and suggested scores in-browser. Shares the data model with the bot via
//! `qc-core`, so there is no schema drift.

use leptos::prelude::*;
use qc_core::{Db, GameMode};

/// Raw GitHub URL of the db backup the bot maintains. Adjust if the
/// owner/repo/branch/path change.
const DB_URL: &str = "https://raw.githubusercontent.com/barakor/discord_qc/db-data/db.json";

const ROW_COUNT: usize = 8;

#[derive(Clone, Default, PartialEq)]
struct Row {
    name: String,
    score: f64,
}

fn main() {
    console_error_panic_hook::set_once();
    leptos::mount::mount_to_body(App);
}

async fn fetch_db() -> Option<Db> {
    let resp = gloo_net::http::Request::get(DB_URL).send().await.ok()?;
    let bytes = resp.binary().await.ok()?;
    Db::from_json(&bytes).ok()
}

#[component]
fn App() -> impl IntoView {
    let db = LocalResource::new(fetch_db);
    let mode = RwSignal::new(GameMode::SacrificeTournament);
    let rows = RwSignal::new(vec![Row::default(); ROW_COUNT]);

    // Mean of score/elo across rows whose player is registered for the mode.
    let avg_factor = Memo::new(move |_| {
        let Some(Some(db)) = db.get() else {
            return None;
        };
        let m = mode.get();
        let factors: Vec<f64> = rows
            .get()
            .iter()
            .filter(|r| !r.name.is_empty())
            .filter_map(|r| {
                db.score_for(&r.name, m)
                    .filter(|e| *e != 0.0)
                    .map(|e| r.score / e)
            })
            .collect();
        (!factors.is_empty()).then(|| factors.iter().sum::<f64>() / factors.len() as f64)
    });

    view! {
        <main style="background:#282828;color:#fbf1c7;min-height:100vh;padding:16px;font-family:Poppins,sans-serif;">
            <h2>"Score Calculator"</h2>

            <ModeRadio mode/>

            <p>
                {move || match avg_factor.get() {
                    Some(f) => format!("avg score factor: {f:.4}"),
                    None => "avg score factor: —".to_string(),
                }}
            </p>

            {move || match db.get() {
                None => view! { <p>"loading player data…"</p> }.into_any(),
                Some(None) => view! { <p>"failed to load player data"</p> }.into_any(),
                Some(Some(db)) => {
                    let names = db.quake_names();
                    view! { <PlayerRows mode rows avg_factor names db/> }.into_any()
                }
            }}
        </main>
    }
}

#[component]
fn ModeRadio(mode: RwSignal<GameMode>) -> impl IntoView {
    view! {
        <div style="display:flex;flex-wrap:wrap;gap:8px;">
            {GameMode::ALL
                .into_iter()
                .map(|m| {
                    let selected = move || mode.get() == m;
                    view! {
                        <button
                            on:click=move |_| mode.set(m)
                            style:background=move || if selected() { "#458588" } else { "#3c3836" }
                            style="color:#fbf1c7;border:none;padding:6px 10px;border-radius:4px;cursor:pointer;"
                        >
                            {m.label()}
                        </button>
                    }
                })
                .collect_view()}
        </div>
    }
}

#[component]
fn PlayerRows(
    mode: RwSignal<GameMode>,
    rows: RwSignal<Vec<Row>>,
    avg_factor: Memo<Option<f64>>,
    names: Vec<String>,
    db: Db,
) -> impl IntoView {
    let options = names
        .iter()
        .map(|n| view! { <option value=n.clone()></option> })
        .collect_view();

    let row_views = (0..ROW_COUNT)
        .map(|i| {
            let db = db.clone();
            // Per-row derived values, recomputed when the row, mode, or avg change.
            let elo = Memo::new({
                let db = db.clone();
                move |_| {
                    let name = rows.with(|rs| rs[i].name.clone());
                    db.score_for(&name, mode.get())
                }
            });
            let score = move || rows.with(|rs| rs[i].score);
            let factor = move || match elo.get() {
                Some(e) if e != 0.0 => Some(score() / e),
                _ => None,
            };
            let suggested = move || match avg_factor.get() {
                Some(avg) if avg != 0.0 => Some(score() / avg),
                _ => None,
            };
            let fmt = |o: Option<f64>| o.map(|v| format!("{v:.3}")).unwrap_or_else(|| "—".into());

            view! {
                <div style="display:flex;align-items:center;gap:8px;margin:4px 0;flex-wrap:wrap;">
                    <input
                        list="players"
                        placeholder="Quake name"
                        prop:value=move || rows.with(|rs| rs[i].name.clone())
                        on:input=move |ev| {
                            let v = event_target_value(&ev);
                            rows.update(|rs| rs[i].name = v);
                        }
                    />
                    <input
                        type="number"
                        placeholder="Game Score"
                        prop:value=move || rows.with(|rs| rs[i].score).to_string()
                        on:input=move |ev| {
                            let v = event_target_value(&ev).parse().unwrap_or(0.0);
                            rows.update(|rs| rs[i].score = v);
                        }
                    />
                    <span>"ELO: " {move || fmt(elo.get())}</span>
                    <span>"factor: " {move || fmt(factor())}</span>
                    <span>"suggested: " {move || fmt(suggested())}</span>
                </div>
            }
        })
        .collect_view();

    view! {
        <datalist id="players">{options}</datalist>
        <div>{row_views}</div>
    }
}
