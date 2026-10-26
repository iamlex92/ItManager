"""Alert bookkeeping shared by the port poller, the trap receiver, the
heartbeat and printer polling. Callers commit, then publish()."""

from collections.abc import Iterable
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Device, DeviceAlert, Port
from .realtime import Hub
from .serialize import alert_out

# Device types whose unreachability is worth an alert. Workstations are
# switched off every evening, so they only change status.
HOST_DOWN_TYPES = {"SWITCH", "SERVER", "PRINTER", "CAMERA", "ACCESS_POINT", "ROUTER", "FIREWALL", "UPS", "VIRTUAL"}


async def _open(session: AsyncSession, device_id, alert_type: str, port_id=None, prefix: str | None = None):
    stmt = select(DeviceAlert).where(
        DeviceAlert.device_id == device_id,
        DeviceAlert.alert_type == alert_type,
        DeviceAlert.is_resolved.is_(False),
    )
    if port_id is not None:
        stmt = stmt.where(DeviceAlert.port_id == port_id)
    if prefix is not None:
        stmt = stmt.where(DeviceAlert.message.startswith(prefix, autoescape=True))
    return list((await session.scalars(stmt)).all())


async def raise_once(
    session: AsyncSession,
    device: Device,
    alert_type: str,
    severity: str,
    message: str,
    port: Port | None = None,
    key_prefix: str | None = None,
) -> DeviceAlert | None:
    """Create an alert unless the same one is already open."""
    if await _open(session, device.id, alert_type, port.id if port else None, key_prefix):
        return None
    alert = DeviceAlert(
        device_id=device.id,
        port_id=port.id if port else None,
        severity=severity,
        alert_type=alert_type,
        message=message,
    )
    session.add(alert)
    return alert


async def resolve(
    session: AsyncSession,
    device: Device,
    alert_type: str,
    now: datetime,
    port: Port | None = None,
    key_prefix: str | None = None,
) -> list[DeviceAlert]:
    alerts = await _open(session, device.id, alert_type, port.id if port else None, key_prefix)
    for a in alerts:
        a.is_resolved = True
        a.resolved_at = now
    return alerts


async def link_changed(
    session: AsyncSession, device: Device, port: Port, went_down: bool, came_up: bool, now: datetime
) -> list[DeviceAlert]:
    """LINK_DOWN on an unexpected drop (not an admin shutdown), resolved when it comes back."""
    if went_down:
        a = await raise_once(
            session,
            device,
            "LINK_DOWN",
            "CRITICAL" if port.is_uplink else "WARNING",
            f"{device.name} {port.port_name}: link down",
            port=port,
        )
        return [a] if a else []
    if came_up:
        return await resolve(session, device, "LINK_DOWN", now, port=port)
    return []


async def reachability_changed(session: AsyncSession, device: Device, online: bool, now: datetime) -> list[DeviceAlert]:
    if device.device_type not in HOST_DOWN_TYPES:
        return []
    if online:
        return await resolve(session, device, "HOST_DOWN", now)
    a = await raise_once(session, device, "HOST_DOWN", "CRITICAL", f"{device.name} is not responding")
    return [a] if a else []


async def publish(session: AsyncSession, hub: Hub, alerts: Iterable[DeviceAlert]) -> None:
    """Call after commit."""
    for a in alerts:
        await session.refresh(a)
        hub.publish(a.device_id, {"type": "alert", "alert": alert_out(a).model_dump(mode="json")})


def publish_device_status(hub: Hub, device: Device) -> None:
    hub.publish(device.id, {"type": "device_status", "device_id": str(device.id), "is_online": device.is_online})
