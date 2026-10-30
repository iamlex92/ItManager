"""«Обновить» on the map: one round of monitoring now instead of waiting for the next poll.

The same sources the background tasks use: switch ports (SSH driver, SNMP, or the emulator), then ping when
monitoring is real. Read-only on the equipment."""

import asyncio
import logging
from typing import Any

from sqlalchemy import select

from ..config import HalMode, MonitorMode
from ..models import Device
from ..schemas import StatusRefreshOut
from . import heartbeat, linkstate, switches

log = logging.getLogger(__name__)

# One source may hang on a dead switch: the others still report.
SOURCE_TIMEOUT = 90


async def _states(sm) -> dict[Any, bool]:
    async with sm() as session:
        rows = await session.execute(select(Device.id, Device.is_online).where(Device.is_active.is_(True)))
        return {i: up for i, up in rows.all()}


async def refresh_now(state: Any) -> StatusRefreshOut:
    settings, sm, hub = state.settings, state.sessionmaker, state.hub
    lock = getattr(state, "status_lock", None)
    if lock is None:
        lock = state.status_lock = asyncio.Lock()
    # Two «Обновить» at once share one round: the second waits and reports what the first found.
    async with lock:
        before = await _states(sm)
        sources: list[str] = []
        errors: list[str] = []

        async def run(name: str, work) -> None:
            sources.append(name)
            try:
                await asyncio.wait_for(work, SOURCE_TIMEOUT)
            except TimeoutError:
                errors.append(f"{name}: не закончился за {SOURCE_TIMEOUT} с")
            except Exception as e:
                log.exception("status refresh: %s failed", name)
                errors.append(f"{name}: {e}")

        if state.links is not None:
            await run("порты коммутаторов (SNMP)", linkstate.poll_once(sm, state.links, hub, set()))
        elif settings.hal_mode is HalMode.REAL:
            await run("порты коммутаторов (SSH)", switches.poll_once(sm, state.driver, hub))
        else:
            await run("эмулятор коммутаторов", switches.poll_once(sm, state.driver, hub))
        if settings.monitor_mode is MonitorMode.REAL:
            pinger = getattr(state, "pinger", None) or heartbeat.icmp_ping
            if pinger is heartbeat.icmp_ping and not heartbeat.ping_available():
                errors.append("ping: на сервере нет программы ping")
            else:
                await run("ping", heartbeat.heartbeat_once(sm, hub, pinger, settings.heartbeat_concurrency))
        after = await _states(sm)
    return StatusRefreshOut(
        checked=len(after),
        online=sum(after.values()),
        changed=sum(1 for i, up in after.items() if before.get(i) is not None and before[i] != up),
        sources=sources,
        errors=errors,
    )
