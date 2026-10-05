CREATE TABLE user_profiles (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    nickname TEXT NOT NULL CHECK(length(nickname) <= 50),
    bio TEXT NOT NULL CHECK(length(bio) <= 500),
    version INTEGER NOT NULL CHECK(version >= 1),
    updated_at TEXT NOT NULL
);
