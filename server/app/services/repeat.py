"""When a repeating task is due next."""

import calendar
from datetime import date, timedelta


def _add_months(d: date, months: int) -> date:
    total = d.year * 12 + (d.month - 1) + months
    year, month = divmod(total, 12)
    month += 1
    return date(year, month, min(d.day, calendar.monthrange(year, month)[1]))


def next_due(due: date, repeat: str, every: int = 1) -> date | None:
    """The date after [due] for «every [every] days/weeks/months/years»; None for a task that does not repeat. A day
    that does not exist in the target month (the 31st, the 29th of February) becomes the month's last day."""
    every = max(1, every)
    if repeat == "daily":
        return due + timedelta(days=every)
    if repeat == "weekly":
        return due + timedelta(weeks=every)
    if repeat == "monthly":
        return _add_months(due, every)
    if repeat == "yearly":
        return _add_months(due, 12 * every)
    return None
