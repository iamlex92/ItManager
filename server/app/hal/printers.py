"""Printer supplies over SNMP (Printer-MIB, RFC 3805). Read-only: only GET
and GETNEXT requests are ever sent."""

import random
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, replace
from typing import Any

# prtMarkerSuppliesTable columns, indexed by <hrDeviceIndex>.<supplyIndex>
SUPPLIES = "1.3.6.1.2.1.43.11.1.1"
COL_TYPE = 5
COL_DESCRIPTION = 6
COL_MAX_CAPACITY = 8
COL_LEVEL = 9
# prtMarkerLifeCount for marker 1 of device 1: total pages printed
LIFE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1"

# prtMarkerSuppliesType values (RFC 3805, PrtMarkerSuppliesTypeTC)
_KIND_BY_TYPE = {3: "TONER", 21: "TONER", 4: "WASTE", 9: "DRUM"}
_COLORS = {
    "black": "#000000",
    "cyan": "#00aeef",
    "magenta": "#ec008c",
    "yellow": "#ffd400",
}
_KIND_COLORS = {"WASTE": "#7f8c8d", "DRUM": "#95a5a6", "OTHER": "#bdc3c7"}


@dataclass(frozen=True)
class Supply:
    name: str
    color_hex: str
    kind: str
    # Remaining: toner left, or free space left in a waste receptacle.
    level_percent: int


@dataclass(frozen=True)
class PrinterSnapshot:
    total_pages: int | None
    supplies: tuple[Supply, ...]


@dataclass(frozen=True)
class PrinterTarget:
    id: uuid.UUID
    name: str
    ip: str | None
    community: str
    # Last stored values; the emulator starts from them.
    last: dict[str, Any]


class PrinterUnreachable(Exception):
    pass


class PrinterDriver(ABC):
    @abstractmethod
    async def poll(self, target: PrinterTarget) -> PrinterSnapshot: ...


def color_for(description: str, kind: str) -> str:
    if kind == "TONER":
        low = description.lower()
        for word, hexv in _COLORS.items():
            if word in low:
                return hexv
        return _COLORS["black"]
    return _KIND_COLORS.get(kind, _KIND_COLORS["OTHER"])


def parse_supplies(rows: dict[str, Any]) -> tuple[Supply, ...]:
    """rows: full OID -> value for the prtMarkerSuppliesTable walk."""
    by_index: dict[str, dict[int, Any]] = {}
    prefix = SUPPLIES + "."
    for oid, value in rows.items():
        if not oid.startswith(prefix):
            continue
        col, _, index = oid[len(prefix) :].partition(".")
        by_index.setdefault(index, {})[int(col)] = value
    out = []
    for index in sorted(by_index, key=lambda i: [int(x) for x in i.split(".")]):
        cols = by_index[index]
        desc = str(cols.get(COL_DESCRIPTION, f"Supply {index}")).strip()
        kind = _KIND_BY_TYPE.get(int(cols.get(COL_TYPE, 1)), "OTHER")
        max_cap = int(cols.get(COL_MAX_CAPACITY, -2))
        level = int(cols.get(COL_LEVEL, -2))
        # -1 other, -2 unknown, -3 "some remaining": no usable percentage.
        if max_cap <= 0 or level < 0:
            continue
        pct = max(0, min(100, round(level * 100 / max_cap)))
        out.append(Supply(name=desc, color_hex=color_for(desc, kind), kind=kind, level_percent=pct))
    return tuple(out)


class EmulatedPrinters(PrinterDriver):
    """Starts from the stored supplies and slowly uses them up."""

    def __init__(self, seed: int | None = None) -> None:
        self._state: dict[uuid.UUID, PrinterSnapshot] = {}
        self._rng = random.Random(seed)

    def _initial(self, target: PrinterTarget) -> PrinterSnapshot:
        supplies = []
        for c in target.last.get("consumables", []):
            name = c["name"]
            kind = c.get("kind") or (
                "WASTE" if "waste" in name.lower() else "DRUM" if "drum" in name.lower() else "TONER"
            )
            supplies.append(Supply(name, c.get("color_hex") or color_for(name, kind), kind, int(c["level_percent"])))
        return PrinterSnapshot(total_pages=target.last.get("total_pages", 0), supplies=tuple(supplies))

    async def poll(self, target: PrinterTarget) -> PrinterSnapshot:
        snap = self._state.get(target.id) or self._initial(target)
        printed = self._rng.randint(0, 40)
        supplies = tuple(
            replace(s, level_percent=max(0, s.level_percent - 1))
            if printed and s.kind != "OTHER" and self._rng.random() < printed / 400
            else s
            for s in snap.supplies
        )
        snap = PrinterSnapshot(total_pages=(snap.total_pages or 0) + printed, supplies=supplies)
        self._state[target.id] = snap
        return snap


def snmp_auth(s, community: str):
    """v2c community, or the v3 USM user from the settings when SNMP_VERSION=3."""
    from pysnmp.hlapi.v3arch import asyncio as h

    if s.snmp_version == "3":
        auth = {
            "MD5": h.usmHMACMD5AuthProtocol,
            "SHA": h.usmHMACSHAAuthProtocol,
            "SHA256": h.usmHMAC192SHA256AuthProtocol,
        }[s.snmp_v3_auth_protocol.value]
        priv = {"DES": h.usmDESPrivProtocol, "AES": h.usmAesCfb128Protocol}[s.snmp_v3_priv_protocol.value]
        return h.UsmUserData(
            s.snmp_v3_user,
            authKey=s.snmp_v3_auth_key or None,
            privKey=s.snmp_v3_priv_key or None,
            authProtocol=auth if s.snmp_v3_auth_key else h.usmNoAuthProtocol,
            privProtocol=priv if s.snmp_v3_priv_key else h.usmNoPrivProtocol,
        )
    return h.CommunityData(community, mpModel=1)


class SnmpPrinters(PrinterDriver):
    """Real Printer-MIB polling with pysnmp (v2c community or v3 USM)."""

    def __init__(self, settings) -> None:
        self._s = settings
        self._engine = None

    def _auth(self, community: str):
        return snmp_auth(self._s, community)

    async def poll(self, target: PrinterTarget) -> PrinterSnapshot:
        from pysnmp.hlapi.v3arch import asyncio as h

        if not target.ip:
            raise PrinterUnreachable(f"{target.name} has no IP address")
        if self._engine is None:
            self._engine = h.SnmpEngine()
        auth = self._auth(target.community)
        transport = await h.UdpTransportTarget.create(
            (target.ip, 161), timeout=self._s.snmp_timeout_seconds, retries=self._s.snmp_retries
        )
        rows: dict[str, Any] = {}
        async for err_ind, err_stat, _idx, var_binds in h.walk_cmd(
            self._engine,
            auth,
            transport,
            h.ContextData(),
            h.ObjectType(h.ObjectIdentity(SUPPLIES)),
            lexicographicMode=False,
        ):
            if err_ind or err_stat:
                raise PrinterUnreachable(f"{target.name}: {err_ind or err_stat.prettyPrint()}")
            for name, value in var_binds:
                try:
                    rows[str(name)] = int(value)
                except (TypeError, ValueError):
                    rows[str(name)] = value.prettyPrint()
        err_ind, err_stat, _idx, var_binds = await h.get_cmd(
            self._engine, auth, transport, h.ContextData(), h.ObjectType(h.ObjectIdentity(LIFE_COUNT))
        )
        total = None
        if not err_ind and not err_stat and var_binds:
            try:
                total = int(var_binds[0][1])
            except (TypeError, ValueError):
                total = None
        return PrinterSnapshot(total_pages=total, supplies=parse_supplies(rows))
