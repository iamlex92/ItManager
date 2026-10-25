"""SNMP trap receiver for linkDown / linkUp (SNMPv1 and v2c).

Passive: it only listens. A trap updates the port's oper state, raises or
resolves LINK_DOWN and pushes the change to WebSocket clients, without
waiting for the next poll."""

import asyncio
import logging
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime

from pyasn1.codec.ber import decoder
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import selectinload

from ..hal.commands import full_interface_name
from ..models import Device, Port
from . import alerts
from .realtime import Hub
from .switches import publish_ports, refresh_end_devices

log = logging.getLogger(__name__)

SNMP_TRAP_OID = "1.3.6.1.6.3.1.1.4.1.0"
LINK_DOWN = "1.3.6.1.6.3.1.1.5.3"
LINK_UP = "1.3.6.1.6.3.1.1.5.4"
IF_INDEX = "1.3.6.1.2.1.2.2.1.1."
IF_DESCR = "1.3.6.1.2.1.2.2.1.2."
IF_NAME = "1.3.6.1.2.1.31.1.1.1.1."


@dataclass(frozen=True)
class LinkTrap:
    community: str
    up: bool
    if_index: int | None
    if_name: str | None
    # v1 traps carry the agent address; v2c relies on the UDP source.
    agent_address: str | None = None


def parse_trap(data: bytes) -> LinkTrap | None:
    """Decode a v1/v2c trap. None when it is not linkDown/linkUp or not a trap."""
    from pysnmp.proto import api

    version = int(api.decodeMessageVersion(data))
    if version not in api.PROTOCOL_MODULES:
        return None
    mod = api.PROTOCOL_MODULES[version]
    msg, _ = decoder.decode(data, asn1Spec=mod.Message())
    community = str(mod.apiMessage.get_community(msg))
    pdu = mod.apiMessage.get_pdu(msg)
    agent = None
    if version == api.SNMP_VERSION_1:
        if not pdu.isSameTypeWith(mod.TrapPDU()):
            return None
        generic = int(mod.apiTrapPDU.get_generic_trap(pdu))
        if generic not in (2, 3):  # linkDown, linkUp
            return None
        up = generic == 3
        agent = mod.apiTrapPDU.get_agent_address(pdu).prettyPrint()
        var_binds = mod.apiTrapPDU.get_varbinds(pdu)
    else:
        if not pdu.isSameTypeWith(mod.SNMPv2TrapPDU()):
            return None
        var_binds = mod.apiPDU.get_varbinds(pdu)
        trap_oid = next((str(v) for o, v in var_binds if str(o) == SNMP_TRAP_OID), None)
        if trap_oid not in (LINK_DOWN, LINK_UP):
            return None
        up = trap_oid == LINK_UP
    if_index = if_name = None
    for oid, value in var_binds:
        o = str(oid)
        if o.startswith(IF_INDEX):
            if_index = int(value)
        elif o.startswith(IF_NAME) or (o.startswith(IF_DESCR) and if_name is None):
            if_name = str(value)
            if_index = if_index or int(o.rsplit(".", 1)[1])
    return LinkTrap(community=community, up=up, if_index=if_index, if_name=if_name, agent_address=agent)


def _norm(name: str) -> str:
    return name.lower().replace(" ", "")


def find_port(device: Device, trap: LinkTrap) -> Port | None:
    if trap.if_name:
        wanted = _norm(trap.if_name)
        for p in device.ports:
            if wanted in (_norm(p.port_name), _norm(full_interface_name(device.vendor or "", p.port_name))):
                return p
    if trap.if_index is not None:
        return next((p for p in device.ports if p.if_index == trap.if_index), None)
    return None


async def handle_trap(
    sm: async_sessionmaker[AsyncSession], hub: Hub, community: str, source_ip: str, data: bytes
) -> bool:
    """Apply one trap. Returns True when it changed a port."""
    try:
        trap = parse_trap(data)
    except Exception:
        log.warning("undecodable SNMP packet from %s", source_ip)
        return False
    if trap is None:
        return False
    if not secrets.compare_digest(trap.community.encode(), community.encode()):
        log.warning("SNMP trap from %s with wrong community ignored", source_ip)
        return False
    async with sm() as session:
        ips = {source_ip} | ({trap.agent_address} if trap.agent_address else set())
        device = await session.scalar(
            select(Device)
            .where(Device.device_type == "SWITCH", Device.ip_address.in_(list(ips)))
            .options(selectinload(Device.ports))
            .limit(1)
        )
        if device is None:
            log.info("trap from unknown device %s ignored", source_ip)
            return False
        port = find_port(device, trap)
        if port is None:
            log.info("trap from %s for unknown interface %s/%s", device.name, trap.if_index, trap.if_name)
            return False
        if port.is_oper_up == trap.up:
            return False
        now = datetime.now(UTC)
        went_down = port.is_oper_up and not trap.up and port.is_admin_up
        came_up = trap.up
        port.is_oper_up = trap.up
        touched = await alerts.link_changed(session, device, port, went_down, came_up, now)
        device.last_seen_at = now
        await session.commit()
        await publish_ports(session, hub, device, [port])
        await alerts.publish(session, hub, touched)
        await refresh_end_devices(session, hub)
        return True


class _TrapProtocol(asyncio.DatagramProtocol):
    def __init__(self, sm, hub: Hub, community: str) -> None:
        self._sm, self._hub, self._community = sm, hub, community
        self._tasks: set[asyncio.Task] = set()

    def datagram_received(self, data: bytes, addr) -> None:
        t = asyncio.create_task(handle_trap(self._sm, self._hub, self._community, addr[0], data))
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)


async def start_trap_listener(
    sm: async_sessionmaker[AsyncSession], hub: Hub, port: int, community: str, host: str = "0.0.0.0"
) -> asyncio.DatagramTransport:
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: _TrapProtocol(sm, hub, community), local_addr=(host, port)
    )
    log.warning("listening for SNMP traps on udp/%s", port)
    return transport
