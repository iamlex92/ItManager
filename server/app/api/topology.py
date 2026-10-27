"""The network as a graph of active devices, for the topology screen."""

from fastapi import APIRouter

from ..schemas import TopologyLinkOut, TopologyNodeOut, TopologyOut
from ..services.topology import NOT_NODES, load_graph, network_links
from .deps import SessionDep

router = APIRouter(prefix="/topology", tags=["topology"])


@router.get("", response_model=TopologyOut)
async def get_topology(session: SessionDep):
    """Switches, routers, servers and end devices with the cables between them traced through sockets
    and patch panels, plus virtual machines tied to their hosts. Read from the inventory only."""
    graph = await load_graph(session)
    nodes = {d.id: d for d in graph.devices.values() if d.device_type not in NOT_NODES}
    links = [TopologyLinkOut(**link) for link in network_links(graph)]
    for d in nodes.values():
        if d.device_type == "VIRTUAL" and d.host_id in nodes:
            links.append(TopologyLinkOut(a_id=d.host_id, b_id=d.id, kind="VIRTUAL"))
    degree: dict = {}
    for link in links:
        degree[link.a_id] = degree.get(link.a_id, 0) + 1
        degree[link.b_id] = degree.get(link.b_id, 0) + 1
    return TopologyOut(
        nodes=[
            TopologyNodeOut(
                id=d.id,
                name=d.name,
                type=d.device_type,
                vendor=d.vendor,
                model=d.model,
                ip=str(d.ip_address) if d.ip_address is not None else None,
                is_online=d.is_online,
                floor_id=d.floor_id,
                host_id=d.host_id,
                degree=degree.get(d.id, 0),
            )
            for d in sorted(nodes.values(), key=lambda d: (d.device_type, d.name))
        ],
        links=links,
    )
