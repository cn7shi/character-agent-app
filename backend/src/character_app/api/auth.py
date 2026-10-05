import hmac
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from character_app.domain.entities import AppError, Principal

bearer = HTTPBearer(auto_error=False)


class DevelopmentIdentityResolver:
    def __init__(self, users: list[tuple[Principal, str]]):
        self.users = users

    def resolve(self, token: str) -> Principal | None:
        for principal, expected in self.users:
            if hmac.compare_digest(token.encode(), expected.encode()):
                return principal
        return None


class RejectingIdentityResolver:
    def resolve(self, token: str) -> None:
        return None


def current_user(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> Principal:
    principal = request.app.state.identities.resolve(credentials.credentials) if credentials else None
    if principal is None:
        raise AppError(401, "AUTH_REQUIRED", "需要有效的访问凭证。")
    return principal
