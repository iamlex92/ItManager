from ..config import HalMode, Settings
from .base import SwitchDriver
from .emulator import EmulatorDriver
from .guard import WriteGuard
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
