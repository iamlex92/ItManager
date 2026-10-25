from enum import StrEnum
from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class HalMode(StrEnum):
    # Emulated switches only. Nothing leaves the server.
    EMULATOR = "emulator"
    # Real Cisco/Eltex gear over SSH. Read-only unless ALLOW_DEVICE_WRITES is set.
    REAL = "real"


class MonitorMode(StrEnum):
    # Printers, heartbeat: answered from the emulator and the database.
    EMULATOR = "emulator"
    # Real SNMP GETs to printers and ICMP echo to every device. Read-only.
    REAL = "real"


class SnmpAuthProtocol(StrEnum):
    MD5 = "MD5"
    SHA = "SHA"
    SHA256 = "SHA256"


class SnmpPrivProtocol(StrEnum):
    DES = "DES"
    AES = "AES"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://netadmin:netadmin@localhost:5432/netadmin"
    api_token: str = Field(min_length=16)

    hal_mode: HalMode = HalMode.EMULATOR
    # Master switch for anything that changes state on REAL equipment
    # (port mode/VLAN, shutdown, bounce, write memory). Off by default.
    # The emulator ignores it: its writes never reach real hardware.
    allow_device_writes: bool = False

    # Background port status polling, seconds. 0 disables the poller.
    poll_interval_seconds: float = 10.0
    # Emulator: chance per switch per poll that one connected access port
    # goes down for a few polls (exercises alerts and live updates).
    emulator_flap_probability: float = 0.1
    emulator_seed: int | None = None
    emulator_bounce_delay_seconds: float = 2.0

    # --- monitoring (all read-only towards devices) ---
    monitor_mode: MonitorMode = MonitorMode.EMULATOR
    # Printer-MIB polling, seconds. 0 disables.
    printer_poll_interval_seconds: float = 300.0
    # ICMP heartbeat (real mode only; the emulator derives reachability
    # from switch ports). 0 disables.
    heartbeat_interval_seconds: float = 15.0
    heartbeat_concurrency: int = 32
    # UDP port for SNMP traps (linkDown/linkUp). 0 disables the listener.
    # 162 needs root or CAP_NET_BIND_SERVICE; the compose file maps 162 -> 1162.
    trap_listen_port: int = 0
    trap_community: str = "public"

    # --- runbooks ---
    # Runbooks (AD, file shares, backups, VMs, services) run against a built-in
    # emulated environment only; nothing reaches AD or real servers yet.
    # Pause per output step, so a run in the app feels like a real one. 0 in tests.
    runbook_step_delay_seconds: float = 0.4

    snmp_version: str = Field(default="2c", pattern="^(2c|3)$")
    snmp_timeout_seconds: float = 2.0
    snmp_retries: int = 1
    snmp_v3_user: str = ""
    snmp_v3_auth_key: str = ""
    snmp_v3_priv_key: str = ""
    snmp_v3_auth_protocol: SnmpAuthProtocol = SnmpAuthProtocol.SHA
    snmp_v3_priv_protocol: SnmpPrivProtocol = SnmpPrivProtocol.AES

    @field_validator("api_token")
    @classmethod
    def _reject_placeholder(cls, v: str) -> str:
        if v.strip().lower() in {"change-me", "changeme", "change-me-please-now"}:
            raise ValueError("API_TOKEN is a placeholder, set a real random token")
        return v

    @property
    def device_writes_enabled(self) -> bool:
        return self.hal_mode is HalMode.EMULATOR or self.allow_device_writes


@lru_cache
def get_settings() -> Settings:
    return Settings()
