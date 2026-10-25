from ..models import Connection, Device, DeviceAlert, Port
from ..schemas import AlertOut, ConnectionOut, DeviceOut, PortOut
from .topology import Graph


def device_out(d: Device, graph: Graph | None = None) -> DeviceOut:
    return DeviceOut(
        id=d.id,
        name=d.name,
        type=d.device_type,
        vendor=d.vendor,
        model=d.model,
        floor_id=d.floor_id,
        x=float(d.pos_x) if d.pos_x is not None else None,
        y=float(d.pos_y) if d.pos_y is not None else None,
        ip=str(d.ip_address) if d.ip_address is not None else None,
        mac=str(d.mac_address) if d.mac_address is not None else None,
        is_online=d.is_online,
        last_seen_at=d.last_seen_at,
        vlan_id=graph.vlan_for(d) if graph else None,
        is_active=d.is_active,
        nfc_tag_uid=d.nfc_tag_uid,
        snmp_community=d.snmp_community,
        metadata=d.metadata_ or {},
    )


def port_out(p: Port, graph: Graph | None = None) -> PortOut:
    return PortOut(
        id=p.id,
        device_id=p.device_id,
        number=p.port_number,
        name=p.port_name,
        is_uplink=p.is_uplink,
        mode=p.mode,
        access_vlan=p.access_vlan,
        tagged_vlans=list(p.tagged_vlans or []),
        is_admin_up=p.is_admin_up,
        is_oper_up=p.is_oper_up,
        speed_mbps=p.speed_mbps,
        poe_enabled=p.poe_enabled,
        connected_mac=str(p.connected_mac) if p.connected_mac else None,
        if_index=p.if_index,
        connected_device_name=graph.connected_device_name(p.id) if graph else None,
    )


def connection_out(c: Connection, graph: Graph) -> ConnectionOut:
    return ConnectionOut(
        id=c.id,
        port_a_id=c.port_a_id,
        port_b_id=c.port_b_id,
        device_a_id=graph.ports[c.port_a_id].device_id,
        device_b_id=graph.ports[c.port_b_id].device_id,
        cable_type=c.cable_type,
        length_meters=float(c.length_meters) if c.length_meters is not None else None,
        label=c.label,
        notes=c.notes,
    )


def alert_out(a: DeviceAlert) -> AlertOut:
    return AlertOut.model_validate(a)
