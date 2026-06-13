# Deploy checklist (Rust bot)

Persistence + logging setup for the Raspberry Pi (Docker, SD card). Applied at
cutover — the Rust service must be dockerized first (see "Prerequisite").

## Persistence model

- **db.json** lives on a durable named volume. Single JSON file, written
  atomically (temp file + `fsync` + `rename`), so a power loss never leaves a
  truncated file. Writes are admin-frequency (a few/day) — negligible SD wear.
- **GitHub is the off-device backstop.** Every mutating command pushes the db
  to GitHub in the background (best-effort). On boot, if the local file is
  missing or corrupt, the bot restores from the GitHub backup and rewrites the
  local copy. SD card death is fully recoverable.

## Logs — RAM only, bounded, still viewable via `docker logs`

Logs are disposable (losing them on reboot is fine), but the full verbose
stream must stay viewable over SSH. Route Docker logging to journald with
journald in volatile (RAM) mode, capped so it can't OOM.

**compose service:**
```yaml
logging:
  driver: journald
```

**host journald** (`/etc/systemd/journald.conf.d/volatile.conf`):
```ini
[Journal]
Storage=volatile
RuntimeMaxUse=200M
```
- `Storage=volatile` → journal lives in `/run/log/journal` (tmpfs, RAM). Lost
  on reboot. Zero SD writes.
- `RuntimeMaxUse=200M` → hard RAM cap; oldest entries rotate out when full, so
  no OOM. Size to the Pi's RAM (200M is safe on a 2GB+ Pi; lower it on a 1GB).
- `docker logs` still works — Docker's journald driver reads entries back.
- Tradeoff: this makes **all** host system logs volatile too. Fine on a
  dedicated bot Pi (same wear win); note it if the Pi does other jobs.

Reload after editing: `sudo systemctl restart systemd-journald`.

## compose — db volume + env

```yaml
services:
  quakebot:
    # build: ./        # needs a Rust Dockerfile (see Prerequisite)
    environment:
      - DISCORD_TOKEN=...
      - DB_PATH=/data/db.json
      - GITHUB_TOKEN=...
      - GITHUB_OWNER=barakor
      - GITHUB_REPO=discord_qc
      - GITHUB_BRANCH=db-data
      - GITHUB_PATH=db.json
    volumes:
      - qcdata:/data
    logging:
      driver: journald
    restart: unless-stopped

volumes:
  qcdata: {}
```

## Discord portal

- Enable the **MESSAGE CONTENT** privileged intent (needed for the pubobot
  listener) or the gateway connection is rejected.

## Webapp (`qc-web` — Leptos score calculator)

Static SPA, no backend. Fetches the same `db.json` the bot pushes to the
GitHub `db-data` branch (URL in `crates/web/src/main.rs`, `DB_URL`) and computes
score factors in-browser. Shares the data model with the bot via `qc-core`.

Build/run:
```bash
rustup target add wasm32-unknown-unknown   # one-time
cargo install trunk                        # one-time
cd crates/web
trunk serve --open                         # dev
trunk build --release                      # → crates/web/dist/ (static bundle)
```
Compile-check without Trunk:
`cargo build -p qc-web --target wasm32-unknown-unknown`.

Host the `dist/` output anywhere static — GitHub Pages, or the Pi serving the
files. The page reads player data straight from GitHub raw; nothing server-side.

Note: never run in a browser yet (no Trunk in the build env) — compiles to wasm
clean, but verify `trunk serve` + the GitHub fetch once before relying on it.

## Prerequisite (separate cutover step, not done yet)

- Replace the Clojure Dockerfile (`FROM clojure:temurin-21-lein`, `lein run`)
  with a Rust build — multi-stage, cross-compiled or built on the Pi for
  `aarch64`. Then swap the compose service from `quakebot-clj` to the Rust
  service and retire the `rocksdb`/`depscache` volumes.
- Migrate existing data: export the Clojure RocksDB (EDN on the `db-data`
  branch) → convert to the Rust `db.json` schema → drop at `DB_PATH` or push to
  the GitHub backup branch so the bot restores it on first boot.
- Retire the ClojureScript webapp (`src/app/`, `src/rewig/`, `shadow-cljs.edn`,
  `package.json`) — superseded by `crates/web`.
