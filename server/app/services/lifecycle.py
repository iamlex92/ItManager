"""What is about to run out: warranties, licenses, certificates and the other dated items, in one list."""

from datetime import UTC, date, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import Settings
from ..models import Deadline, Device, License

# Days left → how loud it is.
WEEK, MONTH, SOON = 7, 30, 60


def level_of(days_left: int) -> str:
    if days_left < 0:
        return "expired"
    if days_left <= WEEK:
        return "week"
    if days_left <= MONTH:
        return "month"
    if days_left <= SOON:
        return "soon"
    return "ok"


def controller_certificate_expiry(settings: Settings) -> date | None:
    """The end of the controller certificate pasted into the settings (AD_CA_PEM); the first of several."""
    pem = (settings.ad_ca_pem or "").strip()
    if not pem:
        return None
    try:
        from cryptography import x509

        cert = x509.load_pem_x509_certificate(pem.encode())
        return cert.not_valid_after_utc.date()
    except Exception:  # noqa: BLE001 - a mangled paste is shown elsewhere; here it simply has no date
        return None


def _item(source: str, kind: str, title: str, due: date, today: date, **extra) -> dict:
    days = (due - today).days
    return {
        "source": source,
        "kind": kind,
        "title": title,
        "due": due,
        "days_left": days,
        "level": level_of(days),
        **extra,
    }


async def collect(
    session: AsyncSession,
    settings: Settings,
    within_days: int = 90,
    today: date | None = None,
    include_expired: bool = True,
) -> list[dict]:
    """Everything due within [within_days] days (and, by default, what is already overdue), soonest first."""
    today = today or datetime.now(UTC).date()
    out: list[dict] = []
    devices = {d.id: d for d in (await session.scalars(select(Device))).all()}

    for d in devices.values():
        if d.warranty_until:
            out.append(
                _item(
                    "warranty",
                    "warranty",
                    f"Гарантия: {d.name}",
                    d.warranty_until,
                    today,
                    ref_id=d.id,
                    device_id=d.id,
                    device_name=d.name,
                )
            )
    for lic in (await session.scalars(select(License))).all():
        if lic.valid_until:
            out.append(_item("license", lic.kind, f"Лицензия: {lic.software}", lic.valid_until, today, ref_id=lic.id))
    for dl in (await session.scalars(select(Deadline).where(Deadline.is_done.is_(False)))).all():
        dev = devices.get(dl.device_id) if dl.device_id else None
        out.append(
            _item(
                "deadline",
                dl.kind,
                dl.title,
                dl.due_date,
                today,
                ref_id=dl.id,
                device_id=dl.device_id,
                device_name=dev.name if dev else None,
            )
        )
    cert = controller_certificate_expiry(settings)
    if cert:
        out.append(_item("certificate", "certificate", "Сертификат контроллера домена (AD_CA_PEM)", cert, today))

    out = [i for i in out if i["days_left"] <= within_days and (include_expired or i["days_left"] >= 0)]
    return sorted(out, key=lambda i: (i["due"], i["title"]))
