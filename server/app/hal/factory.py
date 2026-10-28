import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config import HalMode, MonitorMode, Settings
from .base import SwitchDriver
from .emulator import EmulatorDriver
from .guard import WriteGuard
from .printers import EmulatedPrinters, PrinterDriver, SnmpPrinters
from .real import LoginResolver, SshSwitchDriver
from .ssh import SshOptions, SwitchLogin


def credential_logins(settings: Settings, sessionmaker: async_sessionmaker[AsyncSession] | None) -> LoginResolver:
    """The device's own SSH credential, else the one named by SWITCH_CREDENTIAL."""

    async def resolve(credential_id: uuid.UUID | None) -> SwitchLogin | None:
        from ..models import Credential

        if sessionmaker is None:
            return None
        async with sessionmaker() as s:
            c = await s.get(Credential, credential_id) if credential_id else None
            if c is None and settings.switch_credential:
                c = await s.scalar(select(Credential).where(Credential.name == settings.switch_credential))
        if c is None or not c.username or not c.secret:
            return None
        return SwitchLogin(c.username, c.secret, settings.switch_enable_secret)

    return resolve


def build_driver(settings: Settings, sessionmaker: async_sessionmaker[AsyncSession] | None = None) -> SwitchDriver:
    if settings.hal_mode is HalMode.EMULATOR:
        return EmulatorDriver(
            flap_probability=settings.emulator_flap_probability,
            seed=settings.emulator_seed,
            bounce_delay=settings.emulator_bounce_delay_seconds,
        )
    options = SshOptions(
        port=settings.switch_ssh_port,
        connect_timeout=settings.switch_connect_timeout_seconds,
        command_timeout=settings.switch_command_timeout_seconds,
        known_hosts=settings.switch_known_hosts,
    )
    # Real equipment: always behind the write guard.
    driver = SshSwitchDriver(credential_logins(settings, sessionmaker), options)
    return WriteGuard(driver, allow_writes=settings.allow_device_writes)


def build_printer_driver(settings: Settings) -> PrinterDriver:
    # SNMP GET only, so no write guard is involved.
    if settings.monitor_mode is MonitorMode.REAL:
        return SnmpPrinters(settings)
    return EmulatedPrinters(seed=settings.emulator_seed)
