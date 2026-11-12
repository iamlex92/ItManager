"""A custom script run as a job: it goes on in the background, the web console follows its output and can stop it.

The output of a script on the NetAdmin server arrives as it is written; a script on a computer (WinRM, SSH) returns
its output when it ends, so a job shows «выполняется» until then. Stopping a script on the server kills it; for a
computer it closes the connection and stops waiting: what already started there may finish on its own."""

import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from ..runbooks.custom import MAX_OUTPUT

KEEP_JOBS = 20


@dataclass
class ScriptJob:
    id: uuid.UUID
    runbook_id: str
    title: str
    host: str
    runs_on: str
    operator: str
    started_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    finished_at: datetime | None = None
    # running -> done (the script ended, whatever its code) | stopped (by the operator) | failed (refused or broke)
    state: str = "running"
    exit_code: int | None = None
    stdout: str = ""
    stderr: str = ""
    simulated: bool = False
    audit_id: uuid.UUID | None = None
    error: str = ""
    task: Any = None

    def add(self, kind: str, text: str) -> None:
        """Output as it appears (the server's own scripts)."""
        if kind == "out":
            self.stdout = (self.stdout + text)[:MAX_OUTPUT]
        else:
            self.stderr = (self.stderr + text)[:MAX_OUTPUT]


def registry(state: Any) -> dict[uuid.UUID, ScriptJob]:
    jobs = getattr(state, "script_jobs", None)
    if jobs is None:
        jobs = state.script_jobs = {}
    return jobs


def remember(state: Any, job: ScriptJob) -> None:
    jobs = registry(state)
    jobs[job.id] = job
    finished = [j for j in jobs.values() if j.state != "running"]
    for old in sorted(finished, key=lambda j: j.started_at)[: max(0, len(jobs) - KEEP_JOBS)]:
        jobs.pop(old.id, None)
