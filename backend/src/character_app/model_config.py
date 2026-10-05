import json
import ipaddress
import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError, model_validator

from .config import BACKEND_ROOT


class ModelSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    provider: Literal["mock", "openai_compatible"] = "mock"
    base_url: str = ""
    model_name: str = ""
    api_key: SecretStr = Field(default_factory=lambda: SecretStr(""))
    timeout_seconds: int = Field(default=30, ge=1, le=120)
    max_tokens: int = Field(default=1024, ge=1, le=8192)
    token_limit_parameter: Literal["max_tokens", "max_completion_tokens"] = "max_tokens"

    @model_validator(mode="after")
    def validate_connection(self):
        if self.provider == "mock":
            return self
        url = urlsplit(self.base_url)
        try:
            loopback = ipaddress.ip_address(url.hostname or "").is_loopback
        except ValueError:
            loopback = url.hostname == "localhost"
        if (not url.hostname or url.username or url.password or url.query or url.fragment
                or any(c.isspace() for c in self.base_url)
                or not (url.scheme == "https" or (url.scheme == "http" and loopback))):
            raise ValueError("Use an HTTPS base URL without credentials, query or fragment; HTTP is limited to loopback.")
        if self.base_url.rstrip("/").endswith("/chat/completions"):
            raise ValueError("Configure the API base URL, not the chat/completions endpoint.")
        key = self.api_key.get_secret_value()
        if not key or not key.isascii() or any(c.isspace() or ord(c) < 33 for c in key):
            raise ValueError("A nonempty API credential is required.")
        if not self.model_name.strip() or len(self.model_name) > 200 or any(ord(c) < 32 for c in self.model_name):
            raise ValueError("A valid model name is required.")
        # A model name is public metadata; reject an accidental paste of the credential.
        if key in self.model_name or key in self.base_url:
            raise ValueError("Do not put the API credential in public configuration fields.")
        return self


def load_model_settings(path: Path | None = None) -> ModelSettings:
    path = path or Path(os.environ.get("CHARACTER_MODEL_CONFIG_FILE", str(BACKEND_ROOT / "model.local.json")))
    env_fields = {"provider": "PROVIDER", "base_url": "BASE_URL", "model_name": "NAME",
                  "api_key": "API_KEY", "timeout_seconds": "TIMEOUT_SECONDS", "max_tokens": "MAX_TOKENS",
                  "token_limit_parameter": "TOKEN_LIMIT_PARAMETER"}
    try:
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        if not isinstance(data, dict):
            raise ValueError()
        for field, suffix in env_fields.items():
            value = os.environ.get("CHARACTER_MODEL_" + suffix)
            if value is not None:
                data[field] = value
        return ModelSettings.model_validate(data)
    except (OSError, ValueError, ValidationError):
        # Pydantic/JSON errors can include the raw input. Never chain them into startup logs.
        raise ValueError("Invalid model configuration. Check model.local.json and CHARACTER_MODEL_* settings; values are hidden.") from None
