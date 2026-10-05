from dataclasses import dataclass
import json
import os
from pathlib import Path
import secrets

from character_app.domain.entities import Principal

BACKEND_ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    dev_auth: bool = False

    @classmethod
    def from_env(cls):
        configured = os.environ.get("CHARACTER_DATA_DIR")
        directory = Path(configured).expanduser().resolve() if configured else BACKEND_ROOT / ".runtime" / "app"
        flag = os.environ.get("CHARACTER_DEV_AUTH", "0")
        if flag not in {"0", "1"}:
            raise ValueError("CHARACTER_DEV_AUTH must be 0 or 1")
        return cls(directory, flag == "1")


def load_cursor_key(directory: Path) -> bytes:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "cursor.key"
    try:
        with path.open("x", encoding="utf-8") as handle:
            handle.write(secrets.token_hex(32))
    except FileExistsError:
        pass
    key = bytes.fromhex(path.read_text(encoding="utf-8"))
    if len(key) != 32:
        raise ValueError("Invalid cursor key file")
    return key


def load_development_users(directory: Path) -> list[tuple[Principal, str]]:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "development-users.json"
    try:
        with path.open("x", encoding="utf-8") as handle:
            json.dump([
                {"id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "display_name": "开发用户 A", "token": secrets.token_urlsafe(32)},
                {"id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "display_name": "开发用户 B", "token": secrets.token_urlsafe(32)},
            ], handle, ensure_ascii=False, indent=2)
    except FileExistsError:
        pass
    records = json.loads(path.read_text(encoding="utf-8"))
    if len(records) != 2 or len({row["id"] for row in records}) != 2 or len({row["token"] for row in records}) != 2:
        raise ValueError("Development identities must contain two distinct users and tokens")
    if any(not isinstance(row["token"], str) or len(row["token"]) < 32 for row in records):
        raise ValueError("Invalid development credential file")
    return [(Principal(row["id"], row["display_name"]), row["token"]) for row in records]
