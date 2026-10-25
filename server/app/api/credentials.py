import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Request, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from ..crypto import box
from ..models import Credential, Device, ScriptAuditLog
from ..schemas import CredentialIn, CredentialOut, CredentialUpdate
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/credentials", tags=["credentials"])

# Audit actions; the secret is never part of the parameters.
ACTIONS = {
    "credential_create": "Добавление учётных данных",
    "credential_update": "Изменение учётных данных",
    "credential_delete": "Удаление учётных данных",
}


async def _out(session, c: Credential) -> CredentialOut:
    used = await session.scalar(select(func.count()).select_from(Device).where(Device.ssh_credential_id == c.id))
    return CredentialOut(
        id=c.id,
        name=c.name,
        kind=c.kind,
        username=c.username,
        has_secret=bool(c.secret),
        notes=c.notes,
        devices=used or 0,
        created_at=c.created_at,
        updated_at=c.updated_at,
    )


def _audit(request: Request, action: str, c: Credential, operator: str | None, changed: list[str] | None = None):
    client = request.client.host if request.client else "unknown"
    params = {"name": c.name, "kind": c.kind, "username": c.username, "encrypted": box().enabled}
    if changed is not None:
        params["changed"] = changed
    return ScriptAuditLog(
        script_name=action,
        triggered_by=(f"{operator} · {client}" if operator else f"api:{client}")[:100],
        parameters=params,
        executed_at=datetime.now(UTC),
        exit_code=0,
        output_log=f"{ACTIONS[action]}: {c.name}",
    )


@router.get("", response_model=list[CredentialOut])
async def list_credentials(session: SessionDep):
    """Stored logins without their secrets."""
    rows = (await session.scalars(select(Credential).order_by(Credential.name))).all()
    return [await _out(session, c) for c in rows]


@router.post("", response_model=CredentialOut, status_code=status.HTTP_201_CREATED)
async def create_credential(body: CredentialIn, request: Request, session: SessionDep):
    """Stores a login. The secret is encrypted with SECRET_KEY and can never be read back through the API."""
    c = Credential(name=body.name.strip(), kind=body.kind, username=body.username, secret=body.secret, notes=body.notes)
    session.add(c)
    try:
        await session.flush()
    except IntegrityError as e:
        await session.rollback()
        raise conflict("name_taken", f"учётные данные «{body.name}» уже есть") from e
    session.add(_audit(request, "credential_create", c, body.operator))
    await session.commit()
    await session.refresh(c)
    return await _out(session, c)


@router.patch("/{credential_id}", response_model=CredentialOut)
async def update_credential(credential_id: uuid.UUID, body: CredentialUpdate, request: Request, session: SessionDep):
    c = await session.get(Credential, credential_id)
    if c is None:
        raise not_found("credential")
    data = body.model_dump(exclude_unset=True, exclude={"operator"})
    for k, v in data.items():
        setattr(c, k, v.strip() if k == "name" and v else v)
    try:
        await session.flush()
    except IntegrityError as e:
        await session.rollback()
        raise conflict("name_taken", f"учётные данные «{body.name}» уже есть") from e
    session.add(_audit(request, "credential_update", c, body.operator, changed=sorted(data)))
    await session.commit()
    await session.refresh(c)
    return await _out(session, c)


@router.delete("/{credential_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_credential(
    credential_id: uuid.UUID, request: Request, session: SessionDep, operator: str | None = None
):
    c = await session.get(Credential, credential_id)
    if c is None:
        raise not_found("credential")
    session.add(_audit(request, "credential_delete", c, operator))
    await session.delete(c)
    await session.commit()
