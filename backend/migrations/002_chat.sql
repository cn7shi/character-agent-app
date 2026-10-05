CREATE TABLE runs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    input_message_id TEXT NOT NULL UNIQUE,
    output_message_id TEXT NOT NULL UNIQUE,
    executor TEXT NOT NULL CHECK(executor = 'server'),
    model_name TEXT NOT NULL,
    is_simulated INTEGER NOT NULL CHECK(is_simulated IN (0, 1)),
    status TEXT NOT NULL CHECK(status IN ('queued', 'running', 'cancel_requested', 'completed', 'failed', 'cancelled')),
    last_event_sequence INTEGER NOT NULL DEFAULT 0 CHECK(last_event_sequence >= 0),
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, conversation_id, id),
    FOREIGN KEY(user_id, conversation_id) REFERENCES conversations(user_id, id),
    FOREIGN KEY(user_id, conversation_id, input_message_id) REFERENCES messages(user_id, conversation_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(user_id, conversation_id, output_message_id) REFERENCES messages(user_id, conversation_id, id) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX one_active_run_per_conversation ON runs(conversation_id) WHERE status IN ('queued', 'running', 'cancel_requested');

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    client_message_id TEXT,
    sequence INTEGER NOT NULL CHECK(sequence > 0),
    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
    blocks TEXT NOT NULL CHECK(json_valid(blocks)),
    status TEXT NOT NULL CHECK(status IN ('pending', 'streaming', 'completed', 'failed', 'cancelled')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, conversation_id, id),
    UNIQUE(conversation_id, sequence),
    UNIQUE(run_id, role),
    UNIQUE(user_id, client_message_id),
    CHECK((role = 'user' AND client_message_id IS NOT NULL) OR (role = 'assistant' AND client_message_id IS NULL)),
    FOREIGN KEY(user_id, conversation_id, run_id) REFERENCES runs(user_id, conversation_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE run_events (
    run_id TEXT NOT NULL REFERENCES runs(id),
    sequence INTEGER NOT NULL CHECK(sequence > 0),
    type TEXT NOT NULL,
    data TEXT NOT NULL CHECK(json_valid(data)),
    occurred_at TEXT NOT NULL,
    PRIMARY KEY(run_id, sequence)
);
