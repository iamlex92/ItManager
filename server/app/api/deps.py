import secrets
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings
from ..db import get_session
from ..hal.base import SwitchDriver
from ..services.realtime import Hub

_bearer = HTTPBearer(auto_error=False, description="API_TOKEN from the server config")


def token_ok(settings: Settings, presented: str | None) -> bool:
    if not presented:
        return False
    return secrets.compare_digest(presented.encode(), settings.api_token.encode())


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


async def require_token(
    request: Request,
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> None:
    if creds is None or not token_ok(request.app.state.settings, creds.credentials):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "unauthorized", "message": "missing or invalid API token"},
            headers={"WWW-Authenticate": "Bearer"},
        )


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


def conflict(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=409, detail={"code": code, "message": message})
