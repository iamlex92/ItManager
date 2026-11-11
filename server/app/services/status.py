"""«Обновить» on the map: one round of monitoring now instead of waiting for the next poll.

The same sources the background tasks use: switch ports (SSH driver, SNMP, or the emulator), then ping when
monitoring is real. Read-only on the equipment.

A round is a job: the request waits a few seconds for it and, when it is not done by then, answers with the running
job. Its progress goes to every listener as `status_refresh` events (WebSocket) and is read from
`GET /devices/refresh-status/{id}`. A second «Обновить» while one runs joins it instead of starting another."""

import asyncio
import logging
import uuid
from typing import Any

from sqlalchemy import select

from ..config import HalMode, MonitorMode
from ..models import Device
from ..schemas import StatusRefreshOut
from . import heartbeat, linkstate, switches

log = logging.getLogger(__name__)

# One source may hang on a dead switch: the others still report.
SOURCE_TIMEOUT = 90
# How long a request waits for the round before answering «still running».
WAIT_SECONDS = 8.0
KEEP_JOBS = 5


async def _states(sm) -> dict[Any, bool]:
    async with sm() as session:
        rows = await session.execute(select(Device.id, Device.is_online).where(Device.is_active.is_(True)))
        return {i: up for i, up in rows.all()}


def _jobs(state: Any) -> dict[str, StatusRefreshOut]:
    jobs = getattr(state, "status_jobs", None)
    if jobs is None:
        jobs = state.status_jobs = {}
    return jobs


def _publish(state: Any, job: StatusRefreshOut) -> None:
    state.hub.publish(
        None,
        {"type": "status_refresh", "id": job.id, "state": job.state, "done": job.done, "total": job.total,
         "checked": job.checked, "online": job.online, "changed": job.changed},
    )  # fmt: skip


async def _run(state: Any, job: StatusRefreshOut) -> None:
    settings, sm, hub = state.settings, state.sessionmaker, state.hub
    before = await _states(sm)
    work: list[tuple[str, Any]] = []
    if state.links is not None:
        work.append(("порты коммутаторов (SNMP)", lambda: linkstate.poll_once(sm, state.links, hub, set())))
    elif settings.hal_mode is HalMode.REAL:
        work.append(("порты коммутаторов (SSH)", lambda: switches.poll_once(sm, state.driver, hub)))
    else:
        work.append(("эмулятор коммутаторов", lambda: switches.poll_once(sm, state.driver, hub)))
    if settings.monitor_mode is MonitorMode.REAL:
        pinger = getattr(state, "pinger", None) or heartbeat.icmp_ping
        if pinger is heartbeat.icmp_ping and not heartbeat.ping_available():
            job.errors.append("ping: на сервере нет программы ping")
        else:
            work.append(("ping", lambda: heartbeat.heartbeat_once(sm, hub, pinger, settings.heartbeat_concurrency)))
    job.total = len(work)
    _publish(state, job)

    async def one(name: str, make) -> None:
        job.sources.append(name)
        try:
            await asyncio.wait_for(make(), SOURCE_TIMEOUT)
        except TimeoutError:
            job.errors.append(f"{name}: не закончился за {SOURCE_TIMEOUT} с")
        except Exception as e:
            log.exception("status refresh: %s failed", name)
            job.errors.append(f"{name}: {e}")
        finally:
            job.done += 1
            _publish(state, job)

    # In order: the port state also sets «online» for the devices cabled to a switch, and the ping, which comes last,
    # has the final word. Each source has its own time limit, and the progress shows after each.
    for name, make in work:
        await one(name, make)
    after = await _states(sm)
    job.checked = len(after)
    job.online = sum(after.values())
    job.changed = sum(1 for i, up in after.items() if before.get(i) is not None and before[i] != up)
    job.state = "done"
    _publish(state, job)


def get(state: Any, job_id: str) -> StatusRefreshOut | None:
    return _jobs(state).get(job_id)


async def refresh_now(state: Any, wait: float = WAIT_SECONDS) -> StatusRefreshOut:
    """Starts a round (or joins the one running) and waits up to [wait] seconds for it."""
    jobs = _jobs(state)
    running = next((j for j in jobs.values() if j.state == "running"), None)
    if running is None:
        job = StatusRefreshOut(id=uuid.uuid4().hex, state="running", checked=0, online=0, changed=0, sources=[])
        jobs[job.id] = job
        for old in list(jobs)[:-KEEP_JOBS]:
            jobs.pop(old, None)
        task = asyncio.create_task(_run(state, job))
        state.status_tasks = {*getattr(state, "status_tasks", set()), task}
        task.add_done_callback(lambda t: setattr(state, "status_tasks", getattr(state, "status_tasks", set()) - {t}))
        running = job
        # A round that fails outright must not stay «running» for ever.

        def failed(t: asyncio.Task, job: StatusRefreshOut = job) -> None:
            if not t.cancelled() and t.exception() is not None:
                log.error("status refresh failed: %r", t.exception())
                job.errors.append(f"сбой: {t.exception()}")
                job.state = "done"
                _publish(state, job)

        task.add_done_callback(failed)
    deadline = asyncio.get_running_loop().time() + wait
    while running.state == "running" and asyncio.get_running_loop().time() < deadline:
        await asyncio.sleep(0.05)
    return running
