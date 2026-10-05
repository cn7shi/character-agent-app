from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class UserProfile:
    nickname: str = ""
    bio: str = ""
    version: int = 0


class ProfileStore(Protocol):
    def get(self, user_id: str) -> UserProfile: ...
    def save(self, user_id: str, nickname: str, bio: str, expected_version: int) -> UserProfile: ...
