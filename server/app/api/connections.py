import uuid

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import or_, select

from ..models import Connection, Device, Port
from ..schemas import ConnectionCreate, ConnectionOut, ConnectionUpdate, LinkCreate
from ..services.serialize import connection_out
from ..services.topology import PASSIVE_TYPES, load_graph
from .deps import SessionDep, conflict, not_found

router = APIRouter(prefix="/connections", tags=["connections"])


@router.get("", response_model=list[ConnectionOut])
async def list_connections(session: SessionDep, device_id: uuid.UUID | None = None):
    graph = await load_graph(session)
    stmt = select(Connection)
    if device_id:
        port_ids = select(Port.id).where(Port.device_id == device_id)
        stmt = stmt.where(or_(Connection.port_a_id.in_(port_ids), Connection.port_b_id.in_(port_ids)))
    return [connection_out(c, graph) for c in (await session.scalars(stmt)).all()]


@router.post("", response_model=ConnectionOut, status_code=status.HTTP_201_CREATED)
async def create_connection(body: ConnectionCreate, session: SessionDep):
    """Record a cable in the inventory. Does not touch any device."""
    if body.port_a_id == body.port_b_id:
        raise HTTPException(422, detail={"code": "same_port", "message": "a cable needs two different ports"})
    graph = await load_graph(session)
    for pid in (body.port_a_id, body.port_b_id):
        if pid not in graph.ports:
            raise not_found(f"port {pid}")
        # Active ports take one cable; sockets and patch panel ports take two
        # (front and rear).
        dev = graph.devices[graph.ports[pid].device_id]
        limit = 2 if dev.device_type in PASSIVE_TYPES else 1
        if len(graph.adj.get(pid, [])) >= limit:
            raise conflict("port_busy", f"{dev.name} {graph.ports[pid].port_name} already has a cable")
    conn = Connection(**body.model_dump())
    session.add(conn)
    await session.commit()
    await session.refresh(conn)
    return connection_out(conn, await load_graph(session))


def _cable_limit(device_type: str) -> int:
    # Active ports take one cable; sockets and patch panel ports take two (front and rear).
    return 2 if device_type in PASSIVE_TYPES else 1


def _port_name(device: Device, number: int) -> str:
    if device.device_type in PASSIVE_TYPES:
        return str(number)
    if device.device_type == "SWITCH":
        return f"port{number}"
    return f"eth{number - 1}"


@router.post("/link", response_model=ConnectionOut, status_code=status.HTTP_201_CREATED)
async def link_devices(body: LinkCreate, session: SessionDep):
    """A cable drawn on the map between two devices. Takes a free port on each side; a device
    without one gets a new inventory port, except a switch whose ports are all taken. Inventory
    only: nothing is sent to any device."""
    if body.device_a_id == body.device_b_id:
        raise HTTPException(422, detail={"code": "same_device", "message": "нужны два разных устройства"})
    graph = await load_graph(session)
    devices = []
    for did in (body.device_a_id, body.device_b_id):
        dev = graph.devices.get(did)
        if dev is None:
            raise not_found(f"device {did}")
        devices.append(dev)
    a_ports = {pid for pid, p in graph.ports.items() if p.device_id == devices[0].id}
    if any(
        n in graph.ports and graph.ports[n].device_id == devices[1].id
        for pid in a_ports
        for n in graph.adj.get(pid, [])
    ):
        raise conflict("already_linked", f"{devices[0].name} и {devices[1].name} уже соединены")

    ends: list[Port] = []
    for dev in devices:
        ports = sorted((p for p in graph.ports.values() if p.device_id == dev.id), key=lambda p: p.port_number)
        limit = _cable_limit(dev.device_type)
        free = next((p for p in ports if not p.is_uplink and len(graph.adj.get(p.id, [])) < limit), None)
        free = free or next((p for p in ports if len(graph.adj.get(p.id, [])) < limit), None)
        if free is None:
            if dev.device_type == "SWITCH" and ports:
                raise conflict("no_free_port", f"у {dev.name} нет свободных портов")
            number = max((p.port_number for p in ports), default=0) + 1
            free = Port(device_id=dev.id, port_number=number, port_name=_port_name(dev, number))
            session.add(free)
            await session.flush()
        ends.append(free)
    conn = Connection(port_a_id=ends[0].id, port_b_id=ends[1].id, cable_type=body.cable_type, label=body.label)
    session.add(conn)
    await session.commit()
    await session.refresh(conn)
    return connection_out(conn, await load_graph(session))


@router.get("/{connection_id}", response_model=ConnectionOut)
async def get_connection(connection_id: uuid.UUID, session: SessionDep):
    conn = await session.get(Connection, connection_id)
    if conn is None:
        raise not_found("connection")
    return connection_out(conn, await load_graph(session))


@router.patch("/{connection_id}", response_model=ConnectionOut)
async def update_connection(connection_id: uuid.UUID, body: ConnectionUpdate, session: SessionDep):
    conn = await session.get(Connection, connection_id)
    if conn is None:
        raise not_found("connection")
    for k, v in body.model_dump(exclude_unset=True, mode="json").items():
        if k == "waypoints":
            # Stored without empty floor ids, so a plain route stays [{"x": .., "y": ..}].
            v = [{key: val for key, val in p.items() if val is not None} for p in v or []]
        setattr(conn, k, v)
    await session.commit()
    await session.refresh(conn)
    return connection_out(conn, await load_graph(session))


@router.delete("/{connection_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_connection(connection_id: uuid.UUID, session: SessionDep):
    conn = await session.get(Connection, connection_id)
    if conn is None:
        raise not_found("connection")
    await session.delete(conn)
    await session.commit()
