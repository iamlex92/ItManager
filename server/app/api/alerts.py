import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Query, status
from sqlalchemy import select

from ..models import Device, DeviceAlert
from ..schemas import AlertCreate, AlertOut, Severity
from ..services.serialize import alert_out
from .deps import HubDep, SessionDep, not_found

router = APIRouter(prefix="/alerts", tags=["alerts"])


@router.get("", response_model=list[AlertOut])
async def list_alerts(
    session: SessionDep,
    is_resolved: bool | None = None,
    severity: Severity | None = None,
    device_id: uuid.UUID | None = None,
    limit: int = Query(default=200, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
):
    stmt = select(DeviceAlert).order_by(DeviceAlert.created_at.desc())
    if is_resolved is not None:
        stmt = stmt.where(DeviceAlert.is_resolved.is_(is_resolved))
    if severity:
        stmt = stmt.where(DeviceAlert.severity == severity)
    if device_id:
        stmt = stmt.where(DeviceAlert.device_id == device_id)
    return (await session.scalars(stmt.limit(limit).offset(offset))).all()


@router.post("", response_model=AlertOut, status_code=status.HTTP_201_CREATED)
async def create_alert(body: AlertCreate, session: SessionDep, hub: HubDep):
    if await session.get(Device, body.device_id) is None:
        raise not_found("device")
    alert = DeviceAlert(**body.model_dump())
    session.add(alert)
    await session.commit()
    await session.refresh(alert)
    hub.publish(alert.device_id, {"type": "alert", "alert": alert_out(alert).model_dump(mode="json")})
    return alert


@router.post("/{alert_id}/resolve", response_model=AlertOut)
async def resolve_alert(alert_id: uuid.UUID, session: SessionDep, hub: HubDep):
    alert = await session.get(DeviceAlert, alert_id)
    if alert is None:
        raise not_found("alert")
    if not alert.is_resolved:
        alert.is_resolved = True
        alert.resolved_at = datetime.now(UTC)
        await session.commit()
        await session.refresh(alert)
        hub.publish(alert.device_id, {"type": "alert", "alert": alert_out(alert).model_dump(mode="json")})
    return alert
