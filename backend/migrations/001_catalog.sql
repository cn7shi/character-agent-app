CREATE TABLE users (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE characters (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
    summary TEXT NOT NULL,
    avatar_asset_id TEXT,
    background_asset_id TEXT,
    version INTEGER NOT NULL CHECK(version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE character_revisions (
    id TEXT PRIMARY KEY,
    character_id TEXT NOT NULL REFERENCES characters(id),
    version INTEGER NOT NULL CHECK(version > 0),
    system_prompt TEXT NOT NULL,
    allowed_tool_ids TEXT NOT NULL DEFAULT '[]',
    UNIQUE(character_id, version),
    UNIQUE(character_id, id)
);

CREATE TABLE conversations (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    character_id TEXT NOT NULL REFERENCES characters(id),
    character_revision_id TEXT NOT NULL,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 100),
    version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(character_id, character_revision_id)
        REFERENCES character_revisions(character_id, id),
    UNIQUE(user_id, id)
);
CREATE INDEX conversations_by_user ON conversations(user_id, updated_at DESC, id DESC);
CREATE INDEX characters_by_created ON characters(created_at, id);

CREATE TABLE request_receipts (
    user_id TEXT NOT NULL REFERENCES users(id),
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(user_id, operation, idempotency_key)
);
