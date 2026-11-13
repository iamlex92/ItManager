"""Which silences are alarms. A device that stops answering is an alarm when it is watched: by its type (a switch, a
server) or by the owner's choice, and not paused. A computer switched off for the night is not one."""

from datetime import date

# Device types whose unreachability is worth an alert. Workstations are
# switched off every evening, so they only change status.
HOST_DOWN_TYPES = {"SWITCH", "SERVER", "PRINTER", "CAMERA", "ACCESS_POINT", "ROUTER", "FIREWALL", "UPS", "VIRTUAL"}


def watch_mode(device_type: str, metadata: dict | None) -> str:
    """«always»: a device that stops answering is an alarm; «quiet»: switching it off is normal (a computer). Set in the
    device's metadata «watch»; without it the type decides."""
    chosen = (metadata or {}).get("watch")
    if chosen in ("always", "quiet"):
        return chosen
    return "always" if device_type in HOST_DOWN_TYPES else "quiet"


def snoozed_until(metadata: dict | None, today: date | None = None) -> date | None:
    """The day up to which the device is not expected to answer (a holiday, a repair): metadata «snooze_until», an
    ISO date. None when it is not set, has passed or is not a date."""
    raw = (metadata or {}).get("snooze_until")
    try:
        until = date.fromisoformat(str(raw)[:10]) if raw else None
    except ValueError:
        return None
    return until if until and until >= (today or date.today()) else None


def is_watched(device_type: str, metadata: dict | None, today: date | None = None) -> bool:
    """Whether the silence of this device is an alarm right now: watched by its type or choice, and not paused."""
    return watch_mode(device_type, metadata) == "always" and snoozed_until(metadata, today) is None
