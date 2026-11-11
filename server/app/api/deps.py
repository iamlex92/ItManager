import logging
import secrets
import time
from typing import Annotated
from urllib.parse import unquote

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings
from ..db import get_session
from ..hal.base import SwitchDriver
from ..services.realtime import Hub

log = logging.getLogger(__name__)

_bearer = HTTPBearer(auto_error=False, description="API_TOKEN from the server config")


def token_ok(settings: Settings, presented: str | None) -> bool:
    if not presented:
        return False
    return secrets.compare_digest(presented.encode(), settings.api_token.encode())


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


# Wrong keys from one address: this many in WINDOW seconds block that address for BLOCK seconds.
MAX_FAILURES = 10
FAILURE_WINDOW = 60.0
BLOCK_SECONDS = 60.0


class AuthGuard:
    """Slows down guessing of the API token. Counts wrong keys per client address; past the limit every request from
    that address is refused for a minute, the right key included, so a guess cannot be told from a miss."""

    def __init__(self, clock=time.monotonic) -> None:
        self._clock = clock
        self._seen: dict[str, tuple[int, float]] = {}
        self._blocked: dict[str, float] = {}

    def blocked(self, client: str) -> bool:
        until = self._blocked.get(client)
        if until is None:
            return False
        if self._clock() >= until:
            del self._blocked[client]
            return False
        return True

    def failed(self, client: str) -> bool:
        """Counts a wrong key; True when this one blocks the address."""
        now = self._clock()
        count, since = self._seen.get(client, (0, now))
        if now - since > FAILURE_WINDOW:
            count, since = 0, now
        count += 1
        self._seen[client] = (count, since)
        if count >= MAX_FAILURES:
            self._blocked[client] = now + BLOCK_SECONDS
            del self._seen[client]
            return True
        return False

    def passed(self, client: str) -> None:
        self._seen.pop(client, None)


def _guard(request: Request) -> AuthGuard:
    state = request.app.state
    if getattr(state, "auth_guard", None) is None:
        state.auth_guard = AuthGuard()
    return state.auth_guard


async def record_block(app, client: str) -> None:
    """Said in the log and in the journal: an address was blocked for guessing the key."""
    log.warning("API token: %d wrong keys from %s, blocked for %d s", MAX_FAILURES, client, BLOCK_SECONDS)
    from ..models import ScriptAuditLog

    try:
        async with app.state.sessionmaker() as s:
            s.add(
                ScriptAuditLog(
                    script_name="auth:blocked",
                    triggered_by=f"api:{client}",
                    parameters={"wrong_keys": MAX_FAILURES, "blocked_seconds": int(BLOCK_SECONDS)},
                    exit_code=429,
                    output_log=f"{client}: слишком много неверных ключей, адрес заблокирован на {int(BLOCK_SECONDS)} с",
                )
            )
            await s.commit()
    except Exception:
        log.exception("the block of %s was not written to the journal", client)


async def require_token(
    request: Request,
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> None:
    client = request.client.host if request.client else "unknown"
    guard = _guard(request)
    if guard.blocked(client):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={"code": "too_many_attempts", "message": "Слишком много неверных ключей: подождите минуту"},
            headers={"Retry-After": str(int(BLOCK_SECONDS))},
        )
    if creds is None or not token_ok(request.app.state.settings, creds.credentials):
        if creds is not None and guard.failed(client):
            await record_block(request.app, client)
        # A request with no key at all (a page asking, a probe) is refused without counting.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "unauthorized", "message": "missing or invalid API token"},
            headers={"WWW-Authenticate": "Bearer"},
        )
    guard.passed(client)


def get_driver(request: Request) -> SwitchDriver:
    return request.app.state.driver


def get_hub(request: Request) -> Hub:
    return request.app.state.hub


SessionDep = Annotated[AsyncSession, Depends(get_session)]
SettingsDep = Annotated[Settings, Depends(get_settings_dep)]
DriverDep = Annotated[SwitchDriver, Depends(get_driver)]
HubDep = Annotated[Hub, Depends(get_hub)]


def not_found(what: str) -> HTTPException:
    return HTTPException(status_code=404, detail={"code": "not_found", "message": f"{what} not found"})


OPERATOR_HEADER = "x-operator"


def operator_name(request: Request) -> str:
    """Who the operator says they are («Кто вы» in the web console and the app): the X-Operator header, cleaned.
    Not an identity check: one shared token cannot tell people apart, this only puts a name in the journal."""
    # Percent-encoded UTF-8: a header may only carry ASCII, a name is often Cyrillic.
    raw = unquote(request.headers.get(OPERATOR_HEADER, ""))
    return "".join(ch for ch in raw if ch.isprintable()).strip()[:60]


def who(request: Request, operator: str | None = None) -> str:
    """The journal's «triggered by»: the operator's name and the client address, or the address alone."""
    client = request.client.host if request.client else "unknown"
    # The header (the name the operator entered) wins over the generic label a client puts in the body.
    name = operator_name(request) or (operator or "").strip()
    return (f"{name} · {client}" if name else f"api:{client}")[:100]


def conflict(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=409, detail={"code": code, "message": message})
