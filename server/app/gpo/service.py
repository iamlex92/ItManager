"""«Применить»: gpupdate /force on the computers a GPO reaches, a few at a time, as a job the screens poll."""

import asyncio
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime

from ..pcs.backend import Action, EmulatedPcs, PcError, RealPcs
from ..runbooks.real import Login

APPLY_CONCURRENCY = 8


@dataclass
class ApplyRow:
    host: str
    ok: bool
    error: str = ""


@dataclass
class ApplyJob:
    gpo_id: str
    gpo_name: str
    total: int
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    started_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    status: str = "running"  # running, done
    rows: list[ApplyRow] = field(default_factory=list)
    simulated: bool = False

    @property
    def done(self) -> int:
        return len(self.rows)


async def run_apply(job: ApplyJob, hosts: list[str], pcs: RealPcs | EmulatedPcs, login: Login | None) -> None:
    gate = asyncio.Semaphore(APPLY_CONCURRENCY)

    async def one(host: str) -> None:
        async with gate:
            try:
                r = await pcs.run(host, Action.GPUPDATE, None, login)
                job.rows.append(ApplyRow(host, r.ok, r.error))
            except PcError as e:
                job.rows.append(ApplyRow(host, False, str(e)))
            except Exception as e:  # one unreachable computer must not stop the rest
                job.rows.append(ApplyRow(host, False, f"{type(e).__name__}: {e}"[:300]))

    await asyncio.gather(*(one(h) for h in hosts))
    job.rows.sort(key=lambda r: r.host.lower())
    job.status = "done"
