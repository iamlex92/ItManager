"""Port utilization from stored throughput samples. Reads the database only."""

import uuid
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Query
from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import distinct_on

from ..models import Device, Port, PortTraffic
from ..schemas import DeviceTrafficOut, PortTrafficOut, PortTrafficSummary, TrafficPoint
from .deps import SessionDep, SettingsDep, not_found

router = APIRouter(tags=["traffic"])

Hours = Query(default=24, ge=1, le=24 * 90)


def _utilization(bps: int, speed_mbps: int) -> float:
    return round(100 * bps / (speed_mbps * 1_000_000), 1) if speed_mbps > 0 else 0.0


@router.get("/devices/{device_id}/traffic", response_model=DeviceTrafficOut)
async def device_traffic(device_id: uuid.UUID, session: SessionDep, settings: SettingsDep, hours: int = Hours):
    """Average, peak and latest throughput per port of a switch."""
    if await session.get(Device, device_id) is None:
        raise not_found("device")
    since = datetime.now(UTC) - timedelta(hours=hours)
    ports = (await session.scalars(select(Port).where(Port.device_id == device_id).order_by(Port.port_number))).all()
    stats = {
        row.port_id: row
        for row in (
            await session.execute(
                select(
                    PortTraffic.port_id,
                    func.avg(PortTraffic.in_bps).label("avg_in"),
                    func.avg(PortTraffic.out_bps).label("avg_out"),
                    func.max(PortTraffic.in_bps).label("peak_in"),
                    func.max(PortTraffic.out_bps).label("peak_out"),
                )
                .join(Port, Port.id == PortTraffic.port_id)
                .where(Port.device_id == device_id, PortTraffic.sampled_at >= since)
                .group_by(PortTraffic.port_id)
            )
        ).all()
    }
    last = {
        row.port_id: row
        for row in (
            await session.execute(
                select(PortTraffic.port_id, PortTraffic.in_bps, PortTraffic.out_bps)
                .join(Port, Port.id == PortTraffic.port_id)
                .where(Port.device_id == device_id, PortTraffic.sampled_at >= since)
                .ext(distinct_on(PortTraffic.port_id))
                .order_by(PortTraffic.port_id, PortTraffic.sampled_at.desc())
            )
        ).all()
    }
    out = []
    for p in ports:
        st = stats.get(p.id)
        if st is None:
            continue
        peak_in, peak_out = int(st.peak_in), int(st.peak_out)
        lr = last.get(p.id)
        out.append(
            PortTrafficSummary(
                port_id=p.id,
                port_number=p.port_number,
                port_name=p.port_name,
                is_uplink=p.is_uplink,
                speed_mbps=p.speed_mbps,
                avg_in_bps=int(st.avg_in),
                avg_out_bps=int(st.avg_out),
                peak_in_bps=peak_in,
                peak_out_bps=peak_out,
                peak_utilization=_utilization(max(peak_in, peak_out), p.speed_mbps),
                last_in_bps=lr.in_bps if lr else None,
                last_out_bps=lr.out_bps if lr else None,
            )
        )
    return DeviceTrafficOut(
        device_id=device_id, hours=hours, interval_seconds=settings.traffic_interval_seconds, ports=out
    )


@router.get("/ports/{port_id}/traffic", response_model=PortTrafficOut)
async def port_traffic(
    port_id: uuid.UUID, session: SessionDep, hours: int = Hours, points: int = Query(default=144, ge=10, le=1000)
):
    """Throughput over time for a chart: samples averaged into at most `points` buckets."""
    port = await session.get(Port, port_id)
    if port is None:
        raise not_found("port")
    now = datetime.now(UTC)
    since = now - timedelta(hours=hours)
    rows = (
        await session.execute(
            select(PortTraffic.sampled_at, PortTraffic.in_bps, PortTraffic.out_bps)
            .where(PortTraffic.port_id == port_id, PortTraffic.sampled_at >= since)
            .order_by(PortTraffic.sampled_at)
        )
    ).all()
    bucket = max((hours * 3600) / points, 1.0)
    buckets: dict[int, list[tuple[datetime, int, int]]] = {}
    for at, i, o in rows:
        buckets.setdefault(int((at - since).total_seconds() // bucket), []).append((at, i, o))
    series = [
        TrafficPoint(
            at=group[-1][0],
            in_bps=sum(g[1] for g in group) // len(group),
            out_bps=sum(g[2] for g in group) // len(group),
        )
        for _, group in sorted(buckets.items())
    ]
    peak = max((max(i, o) for _, i, o in rows), default=0)
    return PortTrafficOut(
        port_id=port.id,
        port_name=port.port_name,
        speed_mbps=port.speed_mbps,
        hours=hours,
        peak_utilization=_utilization(peak, port.speed_mbps),
        points=series,
    )
