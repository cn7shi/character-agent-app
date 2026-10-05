from character_app.domain.profile import ProfileStore, UserProfile


class ProfileService:
    def __init__(self, store: ProfileStore):
        self.store = store

    def get(self, user_id: str) -> UserProfile:
        return self.store.get(user_id)

    def save(self, user_id: str, nickname: str, bio: str, expected_version: int) -> UserProfile:
        return self.store.save(user_id, nickname, bio, expected_version)

    def reset(self, user_id: str, expected_version: int) -> UserProfile:
        return self.store.save(user_id, "", "", expected_version)
