from character_app.domain.entities import AppError, not_found
from character_app.domain.profile import UserProfile
from .sqlite import Database, now


class SqliteProfileStore:
    def __init__(self, database: Database):
        self.database = database

    @staticmethod
    def _get(connection, user_id: str) -> UserProfile:
        if not connection.execute("SELECT 1 FROM users WHERE id = ?", (user_id,)).fetchone():
            raise not_found()
        row = connection.execute("SELECT nickname, bio, version FROM user_profiles WHERE user_id = ?", (user_id,)).fetchone()
        return UserProfile(**dict(row)) if row else UserProfile()

    def get(self, user_id: str) -> UserProfile:
        with self.database.connect() as connection:
            return self._get(connection, user_id)

    def save(self, user_id: str, nickname: str, bio: str, expected_version: int) -> UserProfile:
        with self.database.transaction() as connection:
            current = self._get(connection, user_id)
            desired = UserProfile(nickname, bio, expected_version + 1)
            # Immediate exact replay recovers a lost response; later changes always conflict.
            if current == desired:
                return current
            if current.version != expected_version:
                raise AppError(409, "PROFILE_VERSION_CONFLICT", "资料已更新，请读取最新资料后重试。")
            # Keep the version on reset: deleting the row would let an old save recreate stale data.
            connection.execute("""INSERT INTO user_profiles(user_id, nickname, bio, version, updated_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET
                nickname = excluded.nickname, bio = excluded.bio, version = excluded.version,
                updated_at = excluded.updated_at""", (user_id, nickname, bio, desired.version, now()))
            return desired
