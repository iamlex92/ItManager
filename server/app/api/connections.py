import uuid

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import or_, select

from ..models import Connection, Port
from ..schemas import ConnectionCreate, ConnectionOut, ConnectionUpdate
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
    for k, v in body.model_dump(exclude_unset=True).items():
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
