# syntax=docker/dockerfile:1
# Multi-stage build. Build on the Pi (or via buildx) — produces a native
# aarch64 binary for the RPi 4. Dependencies are cached in their own layer so
# code-only changes don't trigger a full recompile (Pi builds are slow).
FROM rust:1.97-trixie AS builder

WORKDIR /app

# Build deps: TLS for octocrab/reqwest, cmake/clang for the gateway's
# simd/zlib features. apt cache mounts keep .debs across base-image bumps so a
# new rust:1-trixie doesn't re-download them; docker-clean would purge the
# archive cache on install, so remove it first.
RUN rm -f /etc/apt/apt.conf.d/docker-clean
RUN --mount=type=cache,id=apt-cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,id=apt-lists,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libssl-dev cmake clang

# sccache: caches compiled crate objects across builds. Wrapper for rustc; its
# cache lives in /sccache, mounted as a cache so it survives between builds.
# cargo-binstall pulls the latest prebuilt sccache binary (no compile).
RUN curl -fsSL https://raw.githubusercontent.com/cargo-bins/cargo-binstall/main/install-from-binstall-release.sh | bash \
    && cargo binstall -y sccache

ENV RUSTC_WRAPPER=sccache
ENV SCCACHE_DIR=/sccache

# Cache dependency compilation: copy every workspace manifest, stub each crate
# root, build deps, then drop the stubs for the real source.
COPY Cargo.toml Cargo.lock ./
COPY crates/core/Cargo.toml crates/core/
COPY crates/web/Cargo.toml crates/web/
RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=cargo-target,target=/app/target \
    --mount=type=cache,id=sccache,target=/sccache \
    mkdir -p src crates/core/src crates/web/src \
    && echo "fn main() {}" > src/main.rs \
    && : > crates/core/src/lib.rs \
    && echo "fn main() {}" > crates/web/src/main.rs \
    && cargo build --release \
    && rm -rf src crates/core/src
# web stub kept on purpose: qc-web is WASM-only and never built here (excluded
# from default-members), but the workspace won't load without a qc-web target.

COPY src ./src
COPY crates/core/src ./crates/core/src
# target/ is a cache mount (absent after the RUN), so copy the binary out to a
# real path for the final stage to pick up.
RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=cargo-target,target=/app/target \
    --mount=type=cache,id=sccache,target=/sccache \
    find src crates/core/src -name '*.rs' -exec touch {} + \
    && cargo build --release \
    && cp target/release/discord_qc /app/discord_qc

FROM debian:trixie-slim

RUN rm -f /etc/apt/apt.conf.d/docker-clean
RUN --mount=type=cache,id=apt-cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,id=apt-lists,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libssl3

# Run as a non-root user; /data holds the persisted db.json.
RUN useradd -r -u 10001 botuser \
    && mkdir -p /data \
    && chown botuser:botuser /data
USER botuser

ENV DB_PATH=/data/db.json

COPY --from=builder /app/discord_qc /usr/local/bin/discord_qc

CMD ["discord_qc"]
