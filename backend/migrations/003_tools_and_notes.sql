CREATE TABLE tool_calls (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    call_id TEXT NOT NULL CHECK(length(call_id) BETWEEN 1 AND 100),
    tool_id TEXT NOT NULL,
    arguments TEXT NOT NULL CHECK(json_valid(arguments)),
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('completed', 'failed')),
    result TEXT CHECK(result IS NULL OR json_valid(result)),
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    UNIQUE(run_id, call_id),
    UNIQUE(user_id, conversation_id, run_id, id),
    FOREIGN KEY(user_id, conversation_id, run_id) REFERENCES runs(user_id, conversation_id, id)
);

CREATE TABLE notes (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    source_run_id TEXT NOT NULL,
    source_tool_call_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 100),
    content TEXT NOT NULL CHECK(length(content) BETWEEN 1 AND 16000),
    created_at TEXT NOT NULL,
    FOREIGN KEY(user_id, conversation_id, source_run_id, source_tool_call_id)
        REFERENCES tool_calls(user_id, conversation_id, run_id, id)
);
CREATE INDEX notes_by_user ON notes(user_id, created_at DESC, id DESC);
CREATE INDEX notes_by_conversation ON notes(user_id, conversation_id, created_at DESC, id DESC);
