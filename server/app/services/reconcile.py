"""Reconciling the key inventory with the license register.

What a scan can see: the whole Windows key (BIOS and registry), only the last five characters of an Office key,
and for 1С the names of the .lic files. So Windows keys are matched exactly, Office keys by their tail, and 1С by the
program's name in the register (a hardware HASP key leaves no trace, the register is the only record then).
"""

import re
import uuid
from dataclasses import dataclass, field
from typing import Any

from ..models import Device, License
from ..pcs.service import matches

OFFICE_TAIL = 5


def norm(key: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", key.upper())


def tokens(key: str | None) -> list[str]:
    """A license field may hold several keys: one per line, or separated by commas, semicolons or spaces."""
    return [t for t in (norm(p) for p in re.split(r"[\s,;]+", key or "")) if t]


def is_one_c(*names: str | None) -> bool:
    text = " ".join(n for n in names if n).lower()
    return "1с" in text or "1c" in text  # Cyrillic and Latin «с»


@dataclass
class Finding:
    """One thing the scan found: a Windows key, an Office tail or a 1С mark, and the computers it stands on."""

    product: str  # windows, office, one_c
    ref: str  # normalized key, the Office tail, or «1С»
    label: str
    hosts: list[str] = field(default_factory=list)


def findings(rows: list[Any]) -> list[Finding]:
    by: dict[tuple[str, str], Finding] = {}

    def add(product: str, ref: str, label: str, host: str) -> None:
        f = by.setdefault((product, ref), Finding(product, ref, label))
        if host not in f.hosts:
            f.hosts.append(host)

    for r in rows:
        if not r.ok:
            continue
        d = r.data
        name = d.get("computer") or r.host
        for k in (d.get("windows_bios_key"), d.get("windows_registry_key")):
            if k and norm(k):
                add("windows", norm(k), "Windows", name)
        for k in d.get("office_keys") or []:
            if norm(k):
                add("office", norm(k), "MS Office", name)
        if d.get("one_c"):
            add("one_c", "1C", "1С:Предприятие", name)
    return list(by.values())


def device_for(devices: list[Device], host: str) -> Device | None:
    return next((d for d in devices if matches(d, host)), None)


def matching_license(f: Finding, lics: list[License]) -> list[License]:
    out = []
    for lic in lics:
        if f.product == "windows" and f.ref in tokens(lic.key):
            out.append(lic)
        elif (
            f.product == "office"
            and len(f.ref) >= OFFICE_TAIL
            and any(t.endswith(f.ref[-OFFICE_TAIL:]) for t in tokens(lic.key) if len(t) > OFFICE_TAIL)
        ):
            out.append(lic)
        elif f.product == "one_c" and is_one_c(lic.software, lic.vendor):
            out.append(lic)
    return out


def hint(f: Finding) -> str:
    if f.product == "one_c":
        return ""
    return f"••••{f.ref[-4:]}"


def reconcile(
    rows: list[Any],
    lics: list[License],
    devices: list[Device],
    linked: dict[uuid.UUID, set[uuid.UUID]],
) -> dict[str, Any]:
    """`linked`: license id → ids of the computers the register says it stands on."""
    scanned = {d.id for r in rows if r.ok for d in [device_for(devices, r.data.get("computer") or r.host)] if d}
    found = findings(rows)
    seen: dict[uuid.UUID, set[uuid.UUID]] = {}  # license → computers where the scan found it
    unmatched = []
    for f in found:
        owners = matching_license(f, lics)
        ids = {d.id for h in f.hosts for d in [device_for(devices, h)] if d}
        if not owners:
            unmatched.append({
                "product": f.product, "ref": f.ref, "label": f.label, "hint": hint(f), "hosts": sorted(f.hosts),
                "adoptable": True,
            })  # fmt: skip
            continue
        for lic in owners:
            seen.setdefault(lic.id, set()).update(ids)
    by_id = {d.id: d for d in devices}
    out = []
    for lic in lics:
        can_see = bool(tokens(lic.key)) or is_one_c(lic.software, lic.vendor)
        have = linked.get(lic.id, set())
        got = seen.get(lic.id, set())
        out.append({
            "id": lic.id, "software": lic.software, "seats": lic.seats, "checkable": can_see,
            "registered": len(have), "found": len(got),
            "to_link": sorted(by_id[i].name for i in got - have),
            # In the register, scanned just now, but nothing of it on the computer.
            "missing": sorted(by_id[i].name for i in (have & scanned) - got) if can_see else [],
            "over": lic.seats is not None and len(have | got) > lic.seats,
        })  # fmt: skip
    return {"licenses": out, "unmatched": unmatched, "scanned": len(scanned)}
