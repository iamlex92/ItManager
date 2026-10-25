from enum import StrEnum
from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class HalMode(StrEnum):
    # Emulated switches only. Nothing leaves the server.
    EMULATOR = "emulator"
    # Real Cisco/Eltex gear over SSH. Read-only unless ALLOW_DEVICE_WRITES is set.
    REAL = "real"


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
