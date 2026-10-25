from ..config import HalMode, MonitorMode, Settings
from .base import SwitchDriver
from .emulator import EmulatorDriver
from .guard import WriteGuard
from .printers import EmulatedPrinters, PrinterDriver, SnmpPrinters
from .real import SshSwitchDriver


def build_driver(settings: Settings) -> SwitchDriver:
    if settings.hal_mode is HalMode.EMULATOR:
        return EmulatorDriver(
            flap_probability=settings.emulator_flap_probability,
            seed=settings.emulator_seed,
            bounce_delay=settings.emulator_bounce_delay_seconds,
        )
    # Real equipment: always behind the write guard.
    return WriteGuard(SshSwitchDriver(), allow_writes=settings.allow_device_writes)


def build_printer_driver(settings: Settings) -> PrinterDriver:
    # SNMP GET only, so no write guard is involved.
    if settings.monitor_mode is MonitorMode.REAL:
        return SnmpPrinters(settings)
    return EmulatedPrinters(seed=settings.emulator_seed)
