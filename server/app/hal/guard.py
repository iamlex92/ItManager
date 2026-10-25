from .base import DeviceTarget, DeviceWritesDisabled, DiagResult, PortConfig, PortRef, PortStatus, SwitchDriver


class WriteGuard(SwitchDriver):
    """Wraps a driver for real equipment and blocks every state-changing call
    unless writes were explicitly enabled in the server config."""

    def __init__(self, inner: SwitchDriver, allow_writes: bool) -> None:
        self._inner = inner
        self._allow_writes = allow_writes

    @property
    def allow_writes(self) -> bool:
        return self._allow_writes

    def _check(self) -> None:
        if not self._allow_writes:
            raise DeviceWritesDisabled("writes to real equipment are disabled (ALLOW_DEVICE_WRITES=false)")

    async def get_port_statuses(self, target: DeviceTarget) -> list[PortStatus]:
        return await self._inner.get_port_statuses(target)

    async def get_running_config(self, target: DeviceTarget) -> str:
        return await self._inner.get_running_config(target)

    async def ping(self, target: DeviceTarget, dest: str, count: int) -> DiagResult:
        return await self._inner.ping(target, dest, count)

    async def traceroute(self, target: DeviceTarget, dest: str) -> DiagResult:
        return await self._inner.traceroute(target, dest)

    async def apply_port_config(self, target: DeviceTarget, port: PortRef, change: PortConfig) -> list[str]:
        self._check()
        return await self._inner.apply_port_config(target, port, change)

    async def bounce_port(self, target: DeviceTarget, port: PortRef) -> list[str]:
        self._check()
        return await self._inner.bounce_port(target, port)

    async def save_config(self, target: DeviceTarget) -> list[str]:
        self._check()
        return await self._inner.save_config(target)

    async def close(self) -> None:
        await self._inner.close()
