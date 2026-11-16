"""The report saved on the server by itself, and what changed since the one before.

A saved report is three files with one stamp: the page, the workbook and a small snapshot (counts and the names of
what is in the inventory). The next report reads the last snapshot and says what was added and what is gone.
"""

import asyncio
import json
import logging
import os
import re
from datetime import date, datetime
from pathlib import Path
from typing import Any

from ..config import Settings
from . import labels
from .data import Options, collect
from .render import render
from .xlsx import build

log = logging.getLogger(__name__)

NAME = re.compile(r"^Report_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.(html|xlsx)$")
STAMP = "%Y-%m-%d_%H-%M"


def folder_of(settings: Settings) -> Path | None:
    if settings.report_dir.strip():
        return Path(settings.report_dir.strip())
    if settings.backup_dir.strip():
        return Path(settings.backup_dir.strip()) / "reports"
    return None


def snapshot(data: dict[str, Any]) -> dict[str, Any]:
    return {
        "at": data["meta"]["generated_at"].isoformat(),
        "counts": data["summary"]["counts"],
        "devices": {d["id"]: d["name"] for d in data["devices_all"]},
        "licenses": sorted(x["software"] for x in data["licenses"]),
        "vlans": sorted(v["id"] for v in data["vlans"]),
    }


def since(prev: dict[str, Any] | None, now: dict[str, Any]) -> dict[str, Any] | None:
    """What differs from the previous snapshot; None when there was none."""
    if not prev:
        return None
    old, new = prev.get("devices", {}), now["devices"]
    renamed = sorted(f"{old[i]} → {new[i]}" for i in new if i in old and old[i] != new[i])
    return {
        "at": prev.get("at"),
        "added": sorted(new[i] for i in new if i not in old),
        "removed": sorted(old[i] for i in old if i not in new),
        "renamed": renamed,
        "licenses_added": sorted(set(now["licenses"]) - set(prev.get("licenses", []))),
        "licenses_removed": sorted(set(prev.get("licenses", [])) - set(now["licenses"])),
        "vlans_added": sorted(set(now["vlans"]) - set(prev.get("vlans", []))),
        "vlans_removed": sorted(set(prev.get("vlans", [])) - set(now["vlans"])),
    }


def saved(folder: Path) -> list[dict[str, Any]]:
    """The saved reports, newest first: one entry per stamp with the files it has."""
    by: dict[str, dict[str, Any]] = {}
    if not folder.is_dir():
        return []
    for f in folder.iterdir():
        if not NAME.match(f.name):
            continue
        stamp = f.stem.removeprefix("Report_")
        e = by.setdefault(stamp, {"stamp": stamp, "files": []})
        e["files"].append({"name": f.name, "kind": f.suffix[1:], "size": f.stat().st_size})
    for e in by.values():
        e["at"] = datetime.strptime(e["stamp"], STAMP).isoformat()
        e["files"].sort(key=lambda x: x["kind"])
    return sorted(by.values(), key=lambda e: e["stamp"], reverse=True)


def _last_snapshot(folder: Path) -> dict[str, Any] | None:
    files = sorted(folder.glob("Report_*.json"))
    if not files:
        return None
    try:
        return json.loads(files[-1].read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def prune(folder: Path, keep: int) -> None:
    for e in saved(folder)[keep:]:
        for suffix in ("html", "xlsx", "json"):
            (folder / f"Report_{e['stamp']}.{suffix}").unlink(missing_ok=True)


async def save_once(sessionmaker, settings: Settings, operator: str = "расписание") -> dict[str, Any]:
    folder = folder_of(settings)
    if folder is None:
        raise RuntimeError("Не задана папка для отчётов: «Настройки сервера» → «Отчёт по расписанию».")
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    async with sessionmaker() as session:
        data = await collect(session, settings, Options(sections=list(labels.SECTION_IDS), operator=operator))
    snap = snapshot(data)
    data["since"] = since(_last_snapshot(folder), snap)
    stamp = data["meta"]["generated_at"].astimezone().strftime(STAMP)
    for suffix, content in (
        ("html", render(data).encode("utf-8")),
        ("xlsx", build(data)),
        ("json", json.dumps(snap, ensure_ascii=False).encode("utf-8")),
    ):
        target = folder / f"Report_{stamp}.{suffix}"
        partial = target.with_name(target.name + ".partial")
        partial.write_bytes(content)
        os.chmod(partial, 0o600)  # the whole inventory: owner-only
        partial.replace(target)
    prune(folder, settings.report_keep)
    return {"stamp": stamp, "folder": str(folder)}


def due(schedule: str, today: date, last: date | None) -> bool:
    """Weekly: once in each calendar week, from Monday. Monthly: once in each month, from the 1st. A day missed
    (the server was off) is caught up on the next one."""
    if schedule == "weekly":
        return last is None or last.isocalendar()[:2] != today.isocalendar()[:2]
    if schedule == "monthly":
        return last is None or (last.year, last.month) != (today.year, today.month)
    return False


def last_saved_on(folder: Path) -> date | None:
    items = saved(folder)
    return datetime.fromisoformat(items[0]["at"]).date() if items else None


async def report_loop(sessionmaker, settings: Settings) -> None:
    await asyncio.sleep(120)
    while True:
        try:
            folder = folder_of(settings)
            if folder is not None and due(settings.report_schedule, date.today(), last_saved_on(folder)):
                r = await save_once(sessionmaker, settings)
                log.warning("report saved: %s in %s", r["stamp"], r["folder"])
        except Exception as e:  # keep the loop alive; the next hour may succeed
            log.error("scheduled report failed: %s", e)
        await asyncio.sleep(3600)
