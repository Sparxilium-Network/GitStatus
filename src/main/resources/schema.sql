-- Schema v1. Statements are separated by ';' and applied idempotently on startup.

CREATE TABLE IF NOT EXISTS configuration_state (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    revision    INTEGER NOT NULL,
    updated_at  TEXT    NOT NULL
);

INSERT OR IGNORE INTO configuration_state (id, revision, updated_at) VALUES (1, 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

CREATE TABLE IF NOT EXISTS discord_destination (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    guild_id    TEXT,
    channel_id  TEXT    NOT NULL,
    bot_profile TEXT    NOT NULL DEFAULT 'default',
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT    NOT NULL,
    -- notification language: 'en' or 'zh-TW'
    language    TEXT    NOT NULL DEFAULT 'en'
);

CREATE TABLE IF NOT EXISTS provider_installation (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    provider    TEXT    NOT NULL,
    external_id TEXT    NOT NULL,
    owner       TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    -- SELECTED: used as a notification source; AVAILABLE: authorized but not selected; REVOKED: no longer returned by the API
    status      TEXT    NOT NULL,
    permissions TEXT    NOT NULL DEFAULT '{}',
    events      TEXT    NOT NULL DEFAULT '[]',
    updated_at  TEXT    NOT NULL,
    UNIQUE (provider, external_id)
);

CREATE TABLE IF NOT EXISTS repository (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    installation_id  INTEGER NOT NULL REFERENCES provider_installation (id) ON DELETE CASCADE,
    external_repo_id TEXT    NOT NULL,
    full_name        TEXT    NOT NULL,
    visibility       TEXT    NOT NULL,
    archived         INTEGER NOT NULL DEFAULT 0,
    -- 0 once the provider API stops returning the repository for this installation
    accessible       INTEGER NOT NULL DEFAULT 1,
    updated_at       TEXT    NOT NULL,
    UNIQUE (installation_id, external_repo_id)
);

CREATE TABLE IF NOT EXISTS subscription (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    repository_id INTEGER NOT NULL UNIQUE REFERENCES repository (id) ON DELETE CASCADE,
    enabled       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS notification_rule (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    repository_id  INTEGER NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    event_type     TEXT    NOT NULL,
    destination_id INTEGER NOT NULL REFERENCES discord_destination (id) ON DELETE CASCADE,
    enabled        INTEGER NOT NULL DEFAULT 1,
    UNIQUE (repository_id, event_type, destination_id)
);

-- Dedupe + limited audit. Never stores raw payload bodies.
CREATE TABLE IF NOT EXISTS delivery_log (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    provider             TEXT    NOT NULL,
    provider_delivery_id TEXT    NOT NULL,
    provider_event       TEXT,
    repo_id              TEXT,
    event_type           TEXT,
    result               TEXT    NOT NULL,
    latency_ms           INTEGER,
    created_at           TEXT    NOT NULL,
    UNIQUE (provider, provider_delivery_id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_log_created ON delivery_log (created_at);

-- Runtime health written by the daemon; not part of the routing configuration.
CREATE TABLE IF NOT EXISTS destination_health (
    destination_id  INTEGER PRIMARY KEY REFERENCES discord_destination (id) ON DELETE CASCADE,
    status          TEXT NOT NULL,
    last_error      TEXT,
    last_attempt_at TEXT,
    last_success_at TEXT
);

CREATE TABLE IF NOT EXISTS runtime_status (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TEXT NOT NULL
)
