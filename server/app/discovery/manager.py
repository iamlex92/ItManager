"""Runs one scan at a time in the background and keeps the last few results
in memory. Every scan and every import is written to the audit log; the SNMP
community never is."""

import asyncio
import contextlib
import ipaddress
import logging
import time
from collections import OrderedDict
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config import DiscoveryMode, Settings
from ..models import ScriptAuditLog
from ..services import heartbeat
from .emulator import EmulatedScanner
from .inventory import load_inventory
from .model import Scan, hosts
from .snmp import SnmpScanner

log = logging.getLogger(__name__)

SCAN_ACTION = "network_scan"
IMPORT_ACTION = "discovery_import"
KEEP = 5


class ScanRunning(Exception):
    def __init__(self, scan: Scan) -> None:
        super().__init__("a scan is already running")
        self.scan = scan


class ScanManager:
    def __init__(self, sm: async_sessionmaker[AsyncSession], settings: Settings) -> None:
        self._sm = sm
        self._settings = settings
        self._scans: OrderedDict = OrderedDict()
        self._task: asyncio.Task | None = None

    @property
    def mode(self) -> str:
        return "real" if self._settings.discovery_mode is DiscoveryMode.REAL else "emulator"

    def get(self, scan_id) -> Scan | None:
        return self._scans.get(scan_id)

    def latest(self) -> Scan | None:
        return next(reversed(self._scans.values()), None)

    def running(self) -> Scan | None:
        return next((s for s in self._scans.values() if s.status == "running"), None)

    async def start(self, nets: list[ipaddress.IPv4Network], community: str, triggered_by: str) -> Scan:
        if (current := self.running()) is not None:
            raise ScanRunning(current)
        scan = Scan(subnets=[str(n) for n in nets], mode=self.mode, triggered_by=triggered_by)
        scan.total = len(hosts(nets))
        scan.started_at = datetime.now(UTC)
        self._scans[scan.id] = scan
        while len(self._scans) > KEEP:
            self._scans.popitem(last=False)
        self._task = asyncio.create_task(self._run(scan, nets, community))
        return scan

    async def _scanner(self):
        if self.mode == "real":
            # Without the ping binary the scan still works, by TCP ports and ARP, and says so in its warnings.
            return SnmpScanner(self._settings, pinger=heartbeat.icmp_ping if heartbeat.ping_available() else None)
        async with self._sm() as session:
            inventory = await load_inventory(session)
        return EmulatedScanner(inventory, self._settings.discovery_step_delay_seconds)

    async def _run(self, scan: Scan, nets: list[ipaddress.IPv4Network], community: str) -> None:
        t0 = time.monotonic()

        def progress(n: int) -> None:
            scan.probed = n

        try:
            scanner = await self._scanner()
            scan.result = await scanner.scan(nets, community, progress)
            scan.status = "done"
        except asyncio.CancelledError:
            scan.status, scan.error = "failed", "сканирование прервано"
            raise
        except Exception as e:
            log.exception("network scan failed")
            scan.status, scan.error = "failed", str(e) or e.__class__.__name__
        finally:
            scan.finished_at = datetime.now(UTC)
            scan.probed = scan.total if scan.status == "done" else scan.probed
            with contextlib.suppress(Exception):
                await self._audit(scan, int((time.monotonic() - t0) * 1000))

    async def _audit(self, scan: Scan, duration_ms: int) -> None:
        r = scan.result
        stdout = "\n".join(
            [
                f"Подсети: {', '.join(scan.subnets)}",
                f"Проверено адресов: {scan.probed} из {scan.total}",
                f"Ответили: {len(r.devices)}, по SNMP: {sum(d.snmp for d in r.devices)}",
                f"Связей найдено: {len(r.links)}",
                *r.warnings,
            ]
        )
        async with self._sm() as session:
            session.add(
                ScriptAuditLog(
                    script_name=SCAN_ACTION,
                    triggered_by=scan.triggered_by[:100],
                    parameters={"subnets": scan.subnets, "mode": scan.mode, "scan_id": str(scan.id)},
                    executed_at=scan.started_at,
                    exit_code=0 if scan.status == "done" else 1,
                    output_log=stdout,
                    stderr_log=scan.error,
                    duration_ms=duration_ms,
                )
            )
            await session.commit()

    async def close(self) -> None:
        if self._task and not self._task.done():
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
