import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass(frozen=True)
class PortRef:
    number: int
    name: str
    is_uplink: bool = False
    mode: str = "ACCESS"
    access_vlan: int | None = 1
    tagged_vlans: tuple[int, ...] = ()
    is_admin_up: bool = True
    is_oper_up: bool = False
    speed_mbps: int = 1000
    poe_enabled: bool = False
    connected_mac: str | None = None


@dataclass(frozen=True)
class DeviceTarget:
    id: uuid.UUID
    name: str
    vendor: str
    ip: str | None
    model: str | None = None
    # The device's own SSH login (stored credential); None: the server's SWITCH_CREDENTIAL.
    credential_id: uuid.UUID | None = None
    # Last known port state from the database. The emulator seeds itself
    # from it; real drivers ignore it.
    ports: tuple[PortRef, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class PortStatus:
    number: int
    name: str
    mode: str
    access_vlan: int | None
    tagged_vlans: tuple[int, ...]
    is_admin_up: bool
    is_oper_up: bool
    speed_mbps: int
    poe_enabled: bool
    connected_mac: str | None


@dataclass(frozen=True)
class PortConfig:
    mode: str | None = None
    access_vlan: int | None = None
    tagged_vlans: tuple[int, ...] | None = None
    is_admin_up: bool | None = None


@dataclass(frozen=True)
class DiagResult:
    command: str
    output: str
    success: bool


class DeviceWritesDisabled(Exception):
    """A state-changing call reached a driver that is not allowed to write."""


class UnsupportedVendor(Exception):
    pass


class SwitchDriver(ABC):
    # --- read-only ---
    @abstractmethod
    async def get_port_statuses(self, target: DeviceTarget) -> list[PortStatus]: ...

    @abstractmethod
    async def get_running_config(self, target: DeviceTarget) -> str: ...

    # Ping / traceroute run from the switch itself (exec mode, no config change).
    @abstractmethod
    async def ping(self, target: DeviceTarget, dest: str, count: int) -> DiagResult: ...

    @abstractmethod
    async def traceroute(self, target: DeviceTarget, dest: str) -> DiagResult: ...

    # --- state-changing: every one of these must be guarded on real gear ---
    @abstractmethod
    async def apply_port_config(self, target: DeviceTarget, port: PortRef, change: PortConfig) -> list[str]: ...

    @abstractmethod
    async def bounce_port(self, target: DeviceTarget, port: PortRef) -> list[str]: ...

    @abstractmethod
    async def save_config(self, target: DeviceTarget) -> list[str]: ...

    async def close(self) -> None:
        return None


class SwitchError(Exception):
    """A real switch could not be reached, refused the login or refused a command.
    [code]: no_switch_login, switch_unreachable, switch_login_failed, switch_timeout, switch_closed, switch_rejected."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
