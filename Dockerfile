# Multi-stage build. Build on the Pi (or via buildx) — produces a native
# aarch64 binary for the RPi 4. Dependencies are cached in their own layer so
# code-only changes don't trigger a full recompile (Pi builds are slow).
FROM rust:1-bookworm AS builder

WORKDIR /app

# Build deps: TLS for octocrab/reqwest, cmake/clang for the gateway's
# simd/zlib features.
RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libssl-dev cmake clang \
    && rm -rf /var/lib/apt/lists/*

# Cache dependency compilation: build against a stub main, then the real source.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs \
    && cargo build --release \
    && rm -rf src

COPY src ./src
RUN touch src/main.rs && cargo build --release

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user; /data holds the persisted db.json.
RUN useradd -r -u 10001 botuser \
    && mkdir -p /data \
    && chown botuser:botuser /data
USER botuser

ENV DB_PATH=/data/db.json

COPY --from=builder /app/target/release/discord_qc /usr/local/bin/discord_qc

CMD ["discord_qc"]
