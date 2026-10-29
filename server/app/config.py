from enum import StrEnum
from functools import lru_cache

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Real switches are polled over SSH (a login per switch), so not more often than this.
REAL_SWITCH_MIN_POLL_SECONDS = 60.0
# Link state over SNMP (MONITOR_MODE=real with emulated switch control): three IF-MIB walks per switch.
SNMP_LINK_MIN_POLL_SECONDS = 15.0


class HalMode(StrEnum):
    # Emulated switches only. Nothing leaves the server.
    EMULATOR = "emulator"
    # Real Cisco/Eltex gear over SSH. Read-only unless ALLOW_DEVICE_WRITES is set.
    REAL = "real"


class RunbookMode(StrEnum):
    # Runbooks act on a built-in emulated AD and file servers. Nothing leaves the server.
    EMULATOR = "emulator"
    # AD over LDAPS and NTFS rights over WinRM. Read-only unless ALLOW_RUNBOOK_WRITES is set.
    REAL = "real"


class MonitorMode(StrEnum):
    # Printers, heartbeat: answered from the emulator and the database.
    EMULATOR = "emulator"
    # Real SNMP GETs to printers and ICMP echo to every device. Read-only.
    REAL = "real"


class DiscoveryMode(StrEnum):
    # Network scan answered from the inventory plus a few made-up devices. Nothing leaves the server.
    EMULATOR = "emulator"
    # Ping, TCP connect to common ports, banners and SNMP GET to the ranges an operator asks for. Read-only.
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

    # Fernet key that encrypts SNMP communities and stored credentials in the database.
    # Generate: openssl rand -base64 32 | tr '+/' '-_'. Empty keeps them plaintext (with a warning).
    secret_key: str = ""

    hal_mode: HalMode = HalMode.EMULATOR
    # Master switch for anything that changes state on REAL equipment
    # (port mode/VLAN, shutdown, bounce, write memory). Off by default.
    # The emulator ignores it: its writes never reach real hardware.
    allow_device_writes: bool = False
    # Real switches: the stored SSH credential (name, «Учётные данные») for switches whose card names none.
    switch_credential: str = ""
    # Real switches: the enable password when the login lands in user mode (>); empty: the login password.
    switch_enable_secret: str = ""
    switch_ssh_port: int = Field(default=22, ge=1, le=65535)
    switch_connect_timeout_seconds: float = 15.0
    switch_command_timeout_seconds: float = 30.0
    # A known_hosts file with the switches' host keys; empty accepts any key (the LAN is trusted).
    switch_known_hosts: str = ""

    # Background port status polling, seconds. 0 disables the poller.
    # Real switches are polled over SSH no more often than every REAL_SWITCH_MIN_POLL_SECONDS.
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
    # Network scan. It only reads (ping, TCP connect, banners, SNMP GET) and runs only when an operator
    # starts it for the ranges they give, so it is real by default even when monitoring is emulated.
    discovery_mode: DiscoveryMode = DiscoveryMode.REAL
    # Emulated network scan: pause per 16 addresses, so progress is visible.
    discovery_step_delay_seconds: float = 0.15
    # Port throughput sampling for the utilization charts, seconds. 0 disables.
    # Real mode reads IF-MIB ifHCInOctets/ifHCOutOctets with SNMP GET; the emulator makes up a working day.
    traffic_interval_seconds: float = 300.0
    traffic_keep_days: int = Field(default=7, ge=1, le=90)
    # UDP port for SNMP traps (linkDown/linkUp). 0 disables the listener.
    # 162 needs root or CAP_NET_BIND_SERVICE; the compose file maps 162 -> 1162.
    trap_listen_port: int = 0
    trap_community: str = "public"

    # --- runbooks ---
    # emulator (default): a built-in emulated AD and file servers.
    # real: AD over LDAPS and folder rights over WinRM (app/runbooks/real.py); backups, VM snapshots
    # and service restarts are not offered in this mode yet.
    runbook_mode: RunbookMode = RunbookMode.EMULATOR
    # Domain computers (WinRM tools): empty follows RUNBOOK_MODE; emulator or real sets them apart.
    pc_mode: str = Field(default="", pattern="^(|emulator|real)$")
    # Real mode only: allow password resets, unlocks and enable/disable in AD. Off: those runbooks
    # show the account and what they would change, nothing more.
    allow_runbook_writes: bool = False
    # Name of the stored credential (⚙ → Учётные данные in the app) used for LDAP and WinRM.
    runbook_credential: str = ""
    # ldaps://dc01.factory.local, or ldap:// with AD_START_TLS=true. Plain LDAP is refused.
    ad_ldap_url: str = ""
    ad_start_tls: bool = False
    # CA certificate (PEM) of the domain controllers, when the system store does not have it.
    ad_ca_file: str = ""
    # The same certificate pasted as text (Samba makes its own: /var/lib/samba/private/tls/ca.pem on the DC).
    ad_ca_pem: str = ""
    # Empty: read from the domain controller.
    ad_base_dn: str = ""
    # Comma-separated file servers the folder-rights runbook may connect to over WinRM: fs01,fs02.
    runbook_file_servers: str = ""
    # Group Policy: the Windows host with the GroupPolicy module (a domain controller or an admin server with
    # GPMC) the server runs the GPO cmdlets on over WinRM. Empty: the domain controller of AD_LDAP_URL.
    gpo_host: str = ""
    # A folder on that host: Backup-GPO before every change, so the previous state can be restored in GPMC.
    gpo_backup_path: str = ""
    winrm_https: bool = False
    # 0: 5985 (HTTP, NTLM-encrypted) or 5986 (HTTPS).
    winrm_port: int = 0
    # Pause per output step in the emulator, so a run in the app feels like a real one. 0 in tests.
    runbook_step_delay_seconds: float = 0.4

    snmp_version: str = Field(default="2c", pattern="^(2c|3)$")
    snmp_timeout_seconds: float = 2.0
    snmp_retries: int = 1
    snmp_v3_user: str = ""
    snmp_v3_auth_key: str = ""
    snmp_v3_priv_key: str = ""
    snmp_v3_auth_protocol: SnmpAuthProtocol = SnmpAuthProtocol.SHA
    snmp_v3_priv_protocol: SnmpPrivProtocol = SnmpPrivProtocol.AES

    # --- Telegram alerts ---
    # Both set: alerts go to this chat. Either empty: Telegram is off (the default).
    telegram_bot_token: str = ""
    telegram_chat_id: str = ""
    # Lowest severity sent: INFO, WARNING or CRITICAL.
    telegram_min_severity: str = Field(default="WARNING", pattern="^(INFO|WARNING|CRITICAL)$")
    # An alert is sent only if it is still open after this many seconds, so a port that flaps
    # and comes back at once does not wake anyone. 0 sends at once.
    telegram_delay_seconds: float = 60.0
    telegram_notify_resolved: bool = True
    # Messages per minute; the rest are counted and mentioned in the next message.
    telegram_max_per_minute: int = 20
    # Optional HTTP(S) proxy when the server has no direct internet access: http://proxy.local:3128
    telegram_proxy: str = ""

    # --- built-in database backups (the Debian and Windows agents; Docker uses its own backup service) ---
    # Folder for pg_dump files. Empty: no built-in backups.
    backup_dir: str = ""
    backup_interval_hours: float = Field(default=24, gt=0)
    backup_keep_days: int = Field(default=14, ge=1)
    # Folder with pg_dump/pg_restore (and initdb/pg_ctl for the bundled database). Empty: search PATH.
    pg_bin_dir: str = ""

    @field_validator("api_token")
    @classmethod
    def _reject_placeholder(cls, v: str) -> str:
        if v.strip().lower() in {"change-me", "changeme", "change-me-please-now"}:
            raise ValueError("API_TOKEN is a placeholder, set a real random token")
        return v

    @field_validator("secret_key")
    @classmethod
    def _valid_secret_key(cls, v: str) -> str:
        from .crypto import valid_key

        v = v.strip()
        if v and not valid_key(v):
            raise ValueError("SECRET_KEY must be 32 url-safe base64 bytes: openssl rand -base64 32 | tr '+/' '-_'")
        return v

    @property
    def telegram_enabled(self) -> bool:
        return bool(self.telegram_bot_token.strip() and self.telegram_chat_id.strip())

    @property
    def runbook_writes_enabled(self) -> bool:
        return self.runbook_mode is RunbookMode.EMULATOR or self.allow_runbook_writes

    @property
    def pcs_real(self) -> bool:
        """Domain computers over real WinRM: PC_MODE, or RUNBOOK_MODE when PC_MODE is empty."""
        return (self.pc_mode or self.runbook_mode.value) == RunbookMode.REAL.value

    @property
    def device_writes_enabled(self) -> bool:
        return self.hal_mode is HalMode.EMULATOR or self.allow_device_writes


@lru_cache
def get_settings() -> Settings:
    return Settings()
