from .base import DeviceTarget, PortConfig, PortRef, PortStatus, SwitchDriver


class SshSwitchDriver(SwitchDriver):
    """Placeholder for the scrapli/netmiko driver for real Cisco and Eltex
    switches. Not implemented yet: every call fails loudly instead of
    pretending to work. It is only ever used wrapped in WriteGuard."""

    def _todo(self) -> NotImplementedError:
        return NotImplementedError("real switch driver is not implemented yet")

    async def get_port_statuses(self, target: DeviceTarget) -> list[PortStatus]:
        raise self._todo()

    async def get_running_config(self, target: DeviceTarget) -> str:
        raise self._todo()

    async def apply_port_config(self, target: DeviceTarget, port: PortRef, change: PortConfig) -> list[str]:
        raise self._todo()

    async def bounce_port(self, target: DeviceTarget, port: PortRef) -> list[str]:
        raise self._todo()

    async def save_config(self, target: DeviceTarget) -> list[str]:
        raise self._todo()
