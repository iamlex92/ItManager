"""Who changed what in a device's record: a snapshot before, a snapshot after, the difference in words.

Only what a person edits is watched (name, addresses, place, lifecycle fields). The position on the map is left out
(dragging an icon is not a change of the record), and so is everything the monitoring writes (online, last seen).
A secret is never written down: its change is recorded as «изменено», nothing more.
"""

from datetime import UTC, datetime
from typing import Any

from ..models import Device, ScriptAuditLog

UPDATE, CREATE, DELETE = "device_update", "device_create", "device_delete"
ACTIONS = {
    UPDATE: "Учёт: изменение устройства",
    CREATE: "Учёт: добавление устройства",
    DELETE: "Учёт: удаление устройства",
}

# attribute -> how it is called on the screen
FIELDS = {
    "name": "Имя", "device_type": "Тип", "vendor": "Производитель", "model": "Модель", "ip_address": "IP",
    "extra_ips": "Другие IP", "mac_address": "MAC", "floor_id": "Этаж", "rack_id": "Стойка", "rack_u": "Юнит",
    "rack_h": "Высота, U", "rack_units": "Высота стойки, U", "host_id": "Работает на", "is_active": "В учёте",
    "serial_number": "Серийный №", "inventory_number": "Инв. №", "purchase_date": "Куплено",
    "warranty_until": "Гарантия до", "supplier": "Поставщик", "assigned_to": "Ответственный",
    "comment": "Комментарий", "ssh_credential_id": "Логин SSH", "nfc_tag_uid": "NFC-метка",
}  # fmt: skip
# Shown as «изменено»: ids mean nothing to a reader, a secret must not be read at all.
OPAQUE = {"floor_id", "rack_id", "host_id", "ssh_credential_id", "snmp_community"}
WATCH_KEYS = ("watch", "snooze_until")


def _text(v: Any) -> str:
    if v is None or v == "" or v == []:
        return "—"
    if isinstance(v, bool):
        return "да" if v else "нет"
    if isinstance(v, list | tuple):
        return ", ".join(str(x) for x in v)
    s = str(v)
    return s if len(s) <= 80 else s[:77] + "…"


def snapshot(d: Device) -> dict[str, Any]:
    snap = {k: getattr(d, k) for k in FIELDS}
    snap["extra_ips"] = [str(i) for i in d.extra_ips or []]
    snap["snmp_community"] = d.snmp_community
    snap["_watch"] = tuple((d.metadata_ or {}).get(k) for k in WATCH_KEYS)
    return snap


def diff(before: dict[str, Any], after: dict[str, Any]) -> list[list[str]]:
    """[[label, old, new], ...] of what differs."""
    out: list[list[str]] = []
    for k, label in FIELDS.items():
        a, b = before.get(k), after.get(k)
        if _text(a) == _text(b):
            continue
        out.append([label, "", "изменено"] if k in OPAQUE else [label, _text(a), _text(b)])
    if before.get("snmp_community") != after.get("snmp_community"):
        out.append(["SNMP-строка", "", "изменена"])
    if before.get("_watch") != after.get("_watch"):
        out.append(["Наблюдение и пауза тревог", "", "изменено"])
    return out


def summary(changes: list[list[str]]) -> str:
    return "; ".join(f"{label}: {old} → {new}" if old else f"{label}: {new}" for label, old, new in changes)


def entry(action: str, device: Device, actor: str, changes: list[list[str]] | None = None) -> ScriptAuditLog:
    changes = changes or []
    return ScriptAuditLog(
        script_name=action,
        triggered_by=actor,
        parameters={"device_id": str(device.id), "device": device.name, "changes": changes},
        executed_at=datetime.now(UTC),
        exit_code=0,
        output_log=f"{ACTIONS[action]}: {device.name}" + (f". {summary(changes)}" if changes else ""),
    )
