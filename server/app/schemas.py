import ipaddress
import uuid
from datetime import datetime
from typing import Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
    model_validator,
)

DeviceType = Literal[
    "SWITCH",
    "WORKSTATION",
    "SERVER",
    "PRINTER",
    "SOCKET",
    "PATCH_PANEL",
    "CAMERA",
    "ACCESS_POINT",
    "ROUTER",
    "FIREWALL",
    "UPS",
    "PHONE",
    "VIRTUAL",
    "RACK",
    "UNKNOWN",
]
PortMode = Literal["ACCESS", "TRUNK"]
Severity = Literal["INFO", "WARNING", "CRITICAL"]

_MAC_RE = r"^[0-9a-fA-F]{2}([:-]?[0-9a-fA-F]{2}){5}$"


def normalize_nfc_uid(v: str | None) -> str | None:
    """Canonical tag UID: uppercase hex bytes joined by colons (04:A2:3B:...)."""
    if v is None:
        return None
    raw = "".join(ch for ch in v if ch not in ":- ").upper()
    if not raw or len(raw) % 2 or len(raw) > 20 or any(ch not in "0123456789ABCDEF" for ch in raw):
        raise ValueError("NFC UID must be 4..10 hex bytes, e.g. 04:A2:3B:1C:5D:80:01")
    if len(raw) < 8:
        raise ValueError("NFC UID must be at least 4 bytes")
    return ":".join(raw[i : i + 2] for i in range(0, len(raw), 2))


def _norm_mac(v: str | None) -> str | None:
    if v is None:
        return None
    raw = v.replace(":", "").replace("-", "").replace(".", "").lower()
    return ":".join(raw[i : i + 2] for i in range(0, 12, 2))


class ORM(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# --- floors ---------------------------------------------------------------


class Drawing(BaseModel):
    """A free line drawn over the map: x, y pairs in map pixels."""

    color: str = Field(default="#FFD60A", pattern=r"^#[0-9a-fA-F]{6}$")
    width: float = Field(default=4, ge=1, le=40)
    points: list[float] = Field(min_length=4, max_length=8000)

    @field_validator("points")
    @classmethod
    def _pairs(cls, v: list[float]) -> list[float]:
        if len(v) % 2:
            raise ValueError("points are x, y pairs")
        return [round(p, 1) for p in v]


MAX_DRAWINGS = 500


class Zone(BaseModel):
    """An area outlined on the map; with is_room set it is a room and lists the devices standing in it."""

    id: str = Field(min_length=1, max_length=40)
    name: str = Field(max_length=100)
    is_room: bool = True
    color: str = Field(default="#0A84FF", pattern=r"^#[0-9a-fA-F]{6}$")
    points: list[float] = Field(min_length=6, max_length=2000)

    @field_validator("points")
    @classmethod
    def _pairs(cls, v: list[float]) -> list[float]:
        if len(v) % 2:
            raise ValueError("points are x, y pairs")
        return [round(p, 1) for p in v]


MAX_ZONES = 200


class Annotation(BaseModel):
    """A mark on the map: a room number in a circle (BADGE), a caption (TEXT) or a pointer (ARROW).

    x, y is where it stands (for an arrow, its tail); an arrow points at x2, y2. Map pixels."""

    id: str = Field(min_length=1, max_length=40)
    kind: Literal["BADGE", "TEXT", "ARROW"]
    x: float
    y: float
    x2: float | None = None
    y2: float | None = None
    text: str = Field(default="", max_length=100)
    color: str = Field(default="#FFD60A", pattern=r"^#[0-9a-fA-F]{6}$")
    # How big it is drawn, 1 is normal.
    size: float = Field(default=1, ge=0.3, le=5)

    @model_validator(mode="after")
    def _arrow_end(self) -> "Annotation":
        if self.kind == "ARROW" and (self.x2 is None or self.y2 is None):
            raise ValueError("an arrow needs x2, y2")
        return self


MAX_ANNOTATIONS = 500


class FloorBase(BaseModel):
    building_name: str = Field(max_length=100)
    floor_number: int
    name: str | None = Field(default=None, max_length=100)
    plan_image_url: str = ""
    scale_pixels_per_meter: float = 50.0
    width_px: int | None = None
    height_px: int | None = None
    plan_x: float | None = None
    plan_y: float | None = None
    plan_w: float | None = Field(default=None, gt=0)
    plan_h: float | None = Field(default=None, gt=0)
    drawings: list[Drawing] = Field(default_factory=list, max_length=MAX_DRAWINGS)
    zones: list[Zone] = Field(default_factory=list, max_length=MAX_ZONES)
    annotations: list[Annotation] = Field(default_factory=list, max_length=MAX_ANNOTATIONS)

    @field_validator("name")
    @classmethod
    def _blank_name(cls, v: str | None) -> str | None:
        # An empty name means "no name": the floor is then shown by its number.
        return (v or "").strip() or None


class FloorCreate(FloorBase):
    pass


class FloorUpdate(BaseModel):
    building_name: str | None = Field(default=None, max_length=100)
    floor_number: int | None = None
    name: str | None = Field(default=None, max_length=100)
    plan_image_url: str | None = None
    scale_pixels_per_meter: float | None = None
    width_px: int | None = None
    height_px: int | None = None
    plan_x: float | None = None
    plan_y: float | None = None
    plan_w: float | None = Field(default=None, gt=0)
    plan_h: float | None = Field(default=None, gt=0)
    drawings: list[Drawing] | None = Field(default=None, max_length=MAX_DRAWINGS)
    zones: list[Zone] | None = Field(default=None, max_length=MAX_ZONES)
    annotations: list[Annotation] | None = Field(default=None, max_length=MAX_ANNOTATIONS)

    @field_validator("name")
    @classmethod
    def _blank_name(cls, v: str | None) -> str | None:
        # An empty name means "no name": the floor is then shown by its number.
        return (v or "").strip() or None


class FloorOut(ORM, FloorBase):
    id: uuid.UUID
    created_at: datetime


# --- vlans ----------------------------------------------------------------


class VlanBase(BaseModel):
    name: str = Field(max_length=50)
    # Empty for a VLAN taken from a switch config without an address on it.
    subnet_cidr: ipaddress.IPv4Network | ipaddress.IPv6Network | None = None
    color_hex: str = Field(default="#3498db", pattern=r"^#[0-9a-fA-F]{6}$")
    description: str | None = None

    @field_serializer("subnet_cidr")
    def _ser_cidr(self, v: Any) -> str | None:
        return str(v) if v is not None else None


class VlanCreate(VlanBase):
    vlan_id: int = Field(ge=1, le=4094)


class VlanUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=50)
    subnet_cidr: ipaddress.IPv4Network | ipaddress.IPv6Network | None = None
    color_hex: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{6}$")
    description: str | None = None


class VlanOut(ORM, VlanBase):
    vlan_id: int


# --- devices --------------------------------------------------------------


class DeviceBase(BaseModel):
    name: str = Field(max_length=100)
    type: DeviceType
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    floor_id: uuid.UUID | None = None
    x: float | None = None
    y: float | None = None
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address | None = None
    mac: str | None = Field(default=None, pattern=_MAC_RE)
    nfc_tag_uid: str | None = Field(default=None, max_length=50)
    snmp_community: str | None = Field(default="public", max_length=50)
    is_active: bool = True
    metadata: dict[str, Any] = Field(default_factory=dict)
    # A virtual machine's host server.
    host_id: uuid.UUID | None = None
    comment: str | None = Field(default=None, max_length=4000)
    # Mounted in a rack: the rack, its lowest unit and the height in units; a rack itself has rack_units.
    rack_id: uuid.UUID | None = None
    rack_u: int | None = Field(default=None, ge=1, le=60)
    rack_h: int | None = Field(default=None, ge=1, le=60)
    rack_units: int | None = Field(default=None, ge=1, le=60)

    @field_validator("mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)

    @field_validator("nfc_tag_uid")
    @classmethod
    def _nfc(cls, v: str | None) -> str | None:
        return normalize_nfc_uid(v)


class DeviceCreate(DeviceBase):
    pass


MAX_BULK = 1000


class DeviceBulkSet(BaseModel):
    """What a group edit changes on every chosen device; fields left out stay as they are."""

    type: DeviceType | None = None
    floor_id: uuid.UUID | None = None
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    is_active: bool | None = None
    host_id: uuid.UUID | None = None


class DeviceBulkUpdate(BaseModel):
    ids: list[uuid.UUID] = Field(min_length=1, max_length=MAX_BULK)
    set: DeviceBulkSet


class DeviceBulkDelete(BaseModel):
    ids: list[uuid.UUID] = Field(min_length=1, max_length=MAX_BULK)


class BulkOut(BaseModel):
    changed: int
    missing: int = 0


class DeviceUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=100)
    type: DeviceType | None = None
    vendor: str | None = None
    model: str | None = None
    floor_id: uuid.UUID | None = None
    x: float | None = None
    y: float | None = None
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address | None = None
    mac: str | None = Field(default=None, pattern=_MAC_RE)
    nfc_tag_uid: str | None = Field(default=None, max_length=50)
    snmp_community: str | None = None
    # A stored SSH credential the (future) real switch driver logs in with.
    ssh_credential_id: uuid.UUID | None = None
    is_active: bool | None = None
    metadata: dict[str, Any] | None = None
    host_id: uuid.UUID | None = None
    comment: str | None = Field(default=None, max_length=4000)
    rack_id: uuid.UUID | None = None
    rack_u: int | None = Field(default=None, ge=1, le=60)
    rack_h: int | None = Field(default=None, ge=1, le=60)
    rack_units: int | None = Field(default=None, ge=1, le=60)

    @field_validator("mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)

    @field_validator("nfc_tag_uid")
    @classmethod
    def _nfc(cls, v: str | None) -> str | None:
        return normalize_nfc_uid(v)


class DeviceOut(BaseModel):
    id: uuid.UUID
    name: str
    type: str
    vendor: str | None
    model: str | None
    floor_id: uuid.UUID | None
    x: float | None
    y: float | None
    ip: str | None
    mac: str | None
    is_online: bool
    last_seen_at: datetime | None
    vlan_id: int | None
    is_active: bool
    nfc_tag_uid: str | None
    # The community itself is write-only (stored encrypted); this only says whether one is set.
    has_snmp_community: bool
    ssh_credential_id: uuid.UUID | None = None
    metadata: dict[str, Any]
    host_id: uuid.UUID | None = None
    comment: str | None = None
    rack_id: uuid.UUID | None = None
    rack_u: int | None = None
    rack_h: int | None = None
    rack_units: int | None = None
    # The switch port this device is cabled to (through sockets and patch panels), if any.
    switch_id: uuid.UUID | None = None
    switch_port_id: uuid.UUID | None = None
    switch_port_name: str | None = None


# --- ports ----------------------------------------------------------------


class PortCreate(BaseModel):
    device_id: uuid.UUID
    number: int = Field(ge=0)
    name: str = Field(max_length=50)
    is_uplink: bool = False
    mode: PortMode = "ACCESS"
    access_vlan: int | None = None
    tagged_vlans: list[int] = Field(default_factory=list)
    is_admin_up: bool = True
    speed_mbps: int = 1000
    poe_enabled: bool = False
    connected_mac: str | None = Field(default=None, pattern=_MAC_RE)
    if_index: int | None = Field(default=None, ge=1)

    @field_validator("connected_mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)


class PortUpdate(BaseModel):
    """A port as recorded in the inventory. Nothing here is sent to the switch."""

    number: int | None = Field(default=None, ge=0)
    name: str | None = Field(default=None, min_length=1, max_length=50)
    is_uplink: bool | None = None
    mode: PortMode | None = None
    access_vlan: int | None = None
    tagged_vlans: list[int] | None = None
    speed_mbps: int | None = Field(default=None, ge=10, le=400_000)
    poe_enabled: bool | None = None


class PortOut(BaseModel):
    id: uuid.UUID
    device_id: uuid.UUID
    number: int
    name: str
    is_uplink: bool
    mode: str
    access_vlan: int | None
    tagged_vlans: list[int]
    is_admin_up: bool
    is_oper_up: bool
    speed_mbps: int
    poe_enabled: bool
    connected_mac: str | None
    if_index: int | None = None
    connected_device_name: str | None = None


class TraceHop(BaseModel):
    device_id: uuid.UUID
    device_name: str
    device_type: str
    port_id: uuid.UUID
    port_name: str


class TraceOut(BaseModel):
    hops: list[TraceHop]


# --- device writes --------------------------------------------------------


class Confirmation(BaseModel):
    # Must be literally true. The client is expected to show the commands and
    # ask the operator (biometric prompt) before sending it.
    confirm: bool = False


class PortConfigChange(BaseModel):
    mode: PortMode | None = None
    access_vlan: int | None = Field(default=None, ge=1, le=4094)
    tagged_vlans: list[int] | None = None
    is_admin_up: bool | None = None

    @field_validator("tagged_vlans")
    @classmethod
    def _vlans(cls, v: list[int] | None) -> list[int] | None:
        if v is None:
            return v
        if any(not 1 <= x <= 4094 for x in v):
            raise ValueError("VLAN ids must be in 1..4094")
        return sorted(set(v))


class PortWriteConfirmation(Confirmation):
    # Second flag required when the port is an uplink.
    confirm_uplink: bool = False


class PortConfigRequest(PortConfigChange, PortWriteConfirmation):
    pass


class CommandsOut(BaseModel):
    commands: list[str]


class TracerouteRequest(BaseModel):
    # An IP address only: it ends up on the switch CLI, so no hostnames.
    target: ipaddress.IPv4Address | ipaddress.IPv6Address


class PingRequest(TracerouteRequest):
    count: int = Field(default=5, ge=1, le=20)


class DiagOut(BaseModel):
    command: str
    output: str
    success: bool


class PortConfigResult(BaseModel):
    port: PortOut
    commands: list[str]


# --- connections ----------------------------------------------------------


MAX_WAYPOINTS = 200


class Waypoint(BaseModel):
    """A bend of a cable on the map, in map pixels of its floor."""

    x: float = Field(ge=-100_000, le=100_000)
    y: float = Field(ge=-100_000, le=100_000)
    # The floor this bend is on, for a cable between floors. None: the floor of end A.
    floor_id: uuid.UUID | None = None

    @field_validator("x", "y")
    @classmethod
    def _round(cls, v: float) -> float:
        return round(v, 1)


class ConnectionCreate(BaseModel):
    port_a_id: uuid.UUID
    port_b_id: uuid.UUID
    cable_type: str = Field(default="Cat5e", max_length=20)
    length_meters: float | None = None
    label: str | None = Field(default=None, max_length=50)
    notes: str | None = None
    color: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{6}$")


class LinkCreate(BaseModel):
    """A cable drawn on the map between two devices; free ports are picked (or added) on each."""

    device_a_id: uuid.UUID
    device_b_id: uuid.UUID
    # A chosen port on either end (say gi1/0/12 of a switch); unset: the server takes a free one.
    port_a_id: uuid.UUID | None = None
    port_b_id: uuid.UUID | None = None
    cable_type: str = Field(default="Cat6", max_length=20)
    label: str | None = Field(default=None, max_length=50)
    color: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{6}$")


class ConnectionUpdate(BaseModel):
    cable_type: str | None = Field(default=None, max_length=20)
    length_meters: float | None = None
    label: str | None = Field(default=None, max_length=50)
    notes: str | None = None
    waypoints: list[Waypoint] | None = Field(default=None, max_length=MAX_WAYPOINTS)
    # null clears it: the map picks the colour again.
    color: str | None = Field(default=None, pattern=r"^#[0-9a-fA-F]{6}$")


class ConnectionOut(BaseModel):
    id: uuid.UUID
    port_a_id: uuid.UUID
    port_b_id: uuid.UUID
    device_a_id: uuid.UUID
    device_b_id: uuid.UUID
    cable_type: str
    length_meters: float | None
    label: str | None
    notes: str | None
    waypoints: list[Waypoint] = []
    color: str | None = None


# --- alerts ---------------------------------------------------------------


class AlertCreate(BaseModel):
    device_id: uuid.UUID
    port_id: uuid.UUID | None = None
    severity: Severity
    alert_type: str = Field(max_length=50)
    message: str


class AlertOut(ORM):
    id: uuid.UUID
    device_id: uuid.UUID
    port_id: uuid.UUID | None
    severity: str
    alert_type: str
    message: str
    is_resolved: bool
    created_at: datetime
    resolved_at: datetime | None


# --- configs --------------------------------------------------------------


class DeviceConfigMeta(ORM):
    id: uuid.UUID
    device_id: uuid.UUID
    config_type: str
    config_hash: str
    captured_at: datetime
    triggered_by: str


class DeviceConfigOut(DeviceConfigMeta):
    content: str


class ConfigDiffOut(BaseModel):
    base_id: uuid.UUID
    base_hash: str
    current_hash: str
    changed: bool
    diff: str


# --- printers -------------------------------------------------------------


SupplyKind = Literal["TONER", "WASTE", "DRUM", "OTHER"]


class Consumable(BaseModel):
    name: str
    color_hex: str
    # Always "remaining": toner left, or free space left in a waste box.
    level_percent: int
    kind: SupplyKind = "OTHER"


class PrinterOut(BaseModel):
    id: uuid.UUID
    name: str
    ip: str | None
    model: str | None
    is_online: bool
    total_pages: int | None
    consumables: list[Consumable]
    low_supply: bool = False
    polled_at: datetime | None = None


# --- misc -----------------------------------------------------------------


class HealthOut(BaseModel):
    status: str
    version: str
    hal_mode: str
    device_writes_enabled: bool
    monitor_mode: str = "emulator"
    # Network scan: real (read-only, started by an operator) or emulator.
    discovery_mode: str = "emulator"
    # emulator, or real (AD over LDAPS, folder rights over WinRM).
    runbook_mode: str = "emulator"
    # Runbooks may change AD: always in the emulator, with ALLOW_RUNBOOK_WRITES in real mode.
    runbook_writes_enabled: bool = True
    # SNMP communities and stored credentials are encrypted at rest (SECRET_KEY is set).
    secrets_encrypted: bool = False
    # Alerts are also sent to a Telegram chat.
    telegram_alerts: bool = False


class ErrorCode(BaseModel):
    code: str
    message: str


# --- NFC ------------------------------------------------------------------


class NfcPayload(BaseModel):
    """NDEF JSON written on the tag (spec 3.4)."""

    app: str
    v: int
    id: uuid.UUID
    type: str | None = None
    name: str | None = None


class NfcResolveRequest(BaseModel):
    payload: NfcPayload | None = None
    uid: str | None = None

    @field_validator("uid")
    @classmethod
    def _uid(cls, v: str | None) -> str | None:
        return normalize_nfc_uid(v)


class NfcBindRequest(BaseModel):
    uid: str
    # Move the tag from whatever device it is bound to now.
    force: bool = False

    @field_validator("uid")
    @classmethod
    def _uid(cls, v: str) -> str:
        return normalize_nfc_uid(v)


# --- runbooks and audit ---


class RunbookChoiceOut(BaseModel):
    value: str
    label: str


class RunbookParamOut(BaseModel):
    name: str
    label: str
    kind: str
    required: bool
    choices: list[RunbookChoiceOut] = []
    placeholder: str | None = None


class RunbookOut(BaseModel):
    id: str
    category: str
    title: str
    description: str
    critical: bool
    params: list[RunbookParamOut]


class RunbookRunIn(BaseModel):
    params: dict[str, str] = {}
    # Required for critical runbooks; the app sets it after biometric confirmation.
    confirm: bool = False
    # How the operator confirmed on the phone ("biometric", "device_credential"), for the audit log.
    auth_method: str | None = Field(default=None, max_length=40)
    # Who or what started the run (the app sends the phone model); the client IP is added.
    operator: str | None = Field(default=None, max_length=60)


class RunbookRunOut(BaseModel):
    audit_id: uuid.UUID
    runbook_id: str
    exit_code: int
    stdout: str
    stderr: str
    # A temporary password: shown to the operator once, never written to the audit log.
    secret: str | None = None
    started_at: datetime
    duration_ms: int
    simulated: bool = True


# --- custom scripts (web console) ---------------------------------------------

ScriptShell = Literal["powershell", "bash", "cmd"]


def _unique_params(params: list["ScriptParam"]) -> list["ScriptParam"]:
    names = [p.name for p in params]
    if len(names) != len(set(names)):
        raise ValueError("parameter names must be unique")
    return params


class ScriptParam(BaseModel):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]{0,30}$")
    label: str = Field(min_length=1, max_length=60)
    required: bool = True
    placeholder: str | None = Field(default=None, max_length=60)


class ScriptBase(BaseModel):
    title: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=500)
    shell: ScriptShell = "powershell"
    body: str = Field(min_length=1, max_length=20_000)
    params: list[ScriptParam] = Field(default_factory=list, max_length=10)
    critical: bool = False

    @field_validator("params")
    @classmethod
    def _unique_names(cls, v: list[ScriptParam]) -> list[ScriptParam]:
        return _unique_params(v)


class ScriptCreate(ScriptBase):
    pass


class ScriptUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=100)
    description: str | None = Field(default=None, max_length=500)
    shell: ScriptShell | None = None
    body: str | None = Field(default=None, min_length=1, max_length=20_000)
    params: list[ScriptParam] | None = Field(default=None, max_length=10)
    critical: bool | None = None

    @field_validator("params")
    @classmethod
    def _unique_names(cls, v: list[ScriptParam] | None) -> list[ScriptParam] | None:
        return None if v is None else _unique_params(v)


class ScriptOut(ScriptBase):
    id: uuid.UUID
    runbook_id: str
    created_at: datetime
    updated_at: datetime


class AuditOut(BaseModel):
    id: uuid.UUID
    action: str
    kind: str
    title: str
    triggered_by: str
    parameters: dict[str, Any] | None
    executed_at: datetime
    exit_code: int | None
    stdout: str | None
    stderr: str | None
    duration_ms: int | None


class ScanIn(BaseModel):
    subnets: list[str] = Field(min_length=1, max_length=8, examples=[["10.99.0.0/24", "10.10.30.0/24"]])
    # Read-only community for SNMP v2c. Used for this scan only: never stored or logged.
    community: str = Field(default="public", min_length=1, max_length=64)
    operator: str | None = Field(default=None, max_length=60)


class FoundDeviceOut(BaseModel):
    ip: str
    name: str | None
    mac: str | None
    vendor: str | None
    model: str | None
    type: str
    description: str | None
    snmp: bool
    # How it was found: ping, tcp (ICMP blocked, a port answered) or arp (only the server's ARP cache knew it).
    seen_by: Literal["ping", "tcp", "arp"] = "ping"
    open_ports: list[int] = []
    # Reverse DNS name.
    hostname: str | None = None
    # Set when a device with this IP is already in the inventory.
    inventory_id: uuid.UUID | None
    inventory_name: str | None


class LinkEndOut(BaseModel):
    name: str
    port: str
    ip: str | None
    inventory_id: uuid.UUID | None


class FoundLinkOut(BaseModel):
    a: LinkEndOut
    b: LinkEndOut
    source: Literal["lldp", "cdp", "fdb"]
    # The inventory already has a cable between these two devices.
    known: bool


class ScanOut(BaseModel):
    id: uuid.UUID
    status: Literal["running", "done", "failed"]
    mode: Literal["emulator", "real"]
    subnets: list[str]
    started_at: datetime | None
    finished_at: datetime | None
    total: int
    probed: int
    devices: list[FoundDeviceOut]
    links: list[FoundLinkOut]
    error: str | None
    # What limited the scan, in plain words (no ping on the server, server outside the subnet, no SNMP).
    warnings: list[str] = []


class ImportIn(BaseModel):
    ips: list[str] = Field(min_length=1, max_length=512)
    links: bool = True
    operator: str | None = Field(default=None, max_length=60)


class ImportedDeviceOut(BaseModel):
    id: uuid.UUID
    name: str
    ip: str


class ImportOut(BaseModel):
    created: list[ImportedDeviceOut]
    links: list[str]
    skipped: list[str]


# --- credentials -------------------------------------------------------------

CredentialKind = Literal["ssh", "snmp_v3", "winrm", "other"]


class CredentialIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    kind: CredentialKind
    username: str | None = Field(default=None, max_length=100)
    # Write-only: stored encrypted, never returned.
    secret: str | None = Field(default=None, max_length=4096)
    notes: str | None = Field(default=None, max_length=2000)
    operator: str | None = Field(default=None, max_length=60)


class CredentialUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    username: str | None = Field(default=None, max_length=100)
    secret: str | None = Field(default=None, max_length=4096)
    notes: str | None = Field(default=None, max_length=2000)
    operator: str | None = Field(default=None, max_length=60)


class CredentialOut(BaseModel):
    id: uuid.UUID
    name: str
    kind: str
    username: str | None
    has_secret: bool
    notes: str | None
    devices: int
    created_at: datetime
    updated_at: datetime


class PortTrafficSummary(BaseModel):
    port_id: uuid.UUID
    port_number: int
    port_name: str
    is_uplink: bool
    speed_mbps: int
    avg_in_bps: int
    avg_out_bps: int
    peak_in_bps: int
    peak_out_bps: int
    # Busiest direction at its peak, percent of the port speed.
    peak_utilization: float
    last_in_bps: int | None
    last_out_bps: int | None


class DeviceTrafficOut(BaseModel):
    device_id: uuid.UUID
    hours: int
    interval_seconds: float
    ports: list[PortTrafficSummary]


class TrafficPoint(BaseModel):
    at: datetime
    in_bps: int
    out_bps: int


class PortTrafficOut(BaseModel):
    port_id: uuid.UUID
    port_name: str
    speed_mbps: int
    hours: int
    peak_utilization: float
    points: list[TrafficPoint]


class HistoryEntry(BaseModel):
    at: datetime
    # change: applied; blocked: refused by ALLOW_DEVICE_WRITES; failed: error on the device;
    # config_backup: a stored running-config.
    kind: Literal["change", "blocked", "failed", "config_backup"]
    action: str
    title: str
    summary: str
    actor: str
    commands: list[str] = []


# --- CLI import -------------------------------------------------------------


class ConfigImportIn(BaseModel):
    text: str = Field(min_length=1, max_length=2_000_000, description="pasted running-config or show vlan brief output")
    apply: bool = Field(default=False, description="false: only show what would change")


class ConfigImportPort(BaseModel):
    port: str
    matched: bool
    changes: dict[str, list[Any]] = Field(default_factory=dict, description="field: [current, from the text]")


class ConfigImportOut(BaseModel):
    kind: str
    hostname: str | None = None
    applied: bool
    ports: list[ConfigImportPort]
    unmatched_interfaces: list[str]
    unknown_vlans: dict[int, str] = Field(
        default_factory=dict, description="kept for older apps; VLANs missing from the inventory are in new_vlans"
    )
    new_vlans: dict[int, str] = Field(
        default_factory=dict, description="VLANs from the text the inventory does not have: added on apply"
    )
    new_ports: list[str] = Field(default_factory=list, description="interfaces with no port yet: added on apply")
    changed_ports: int


# --- peripherals ----------------------------------------------------------------

PeripheralKind = Literal[
    "MONITOR",
    "KEYBOARD",
    "MOUSE",
    "DOCK",
    "HEADSET",
    "WEBCAM",
    "SPEAKERS",
    "SCANNER",
    "PRINTER",
    "PROJECTOR",
    "UPS",
    "OTHER",
]


class PeripheralBase(BaseModel):
    kind: PeripheralKind
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    specs: str | None = Field(default=None, max_length=200)
    serial_number: str | None = Field(default=None, max_length=100)
    inventory_number: str | None = Field(default=None, max_length=100)
    comment: str | None = Field(default=None, max_length=4000)
    # The device it is attached to (usually a computer); empty: in stock.
    device_id: uuid.UUID | None = None


class PeripheralCreate(PeripheralBase):
    pass


class PeripheralUpdate(BaseModel):
    kind: PeripheralKind | None = None
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    specs: str | None = Field(default=None, max_length=200)
    serial_number: str | None = Field(default=None, max_length=100)
    inventory_number: str | None = Field(default=None, max_length=100)
    comment: str | None = Field(default=None, max_length=4000)
    device_id: uuid.UUID | None = None


class PeripheralOut(ORM, PeripheralBase):
    id: uuid.UUID
    device_name: str | None = None
    created_at: datetime
    updated_at: datetime


# --- topology ---------------------------------------------------------------------


class TopologyNodeOut(BaseModel):
    id: uuid.UUID
    name: str
    type: str
    vendor: str | None
    model: str | None
    ip: str | None
    is_online: bool
    floor_id: uuid.UUID | None
    host_id: uuid.UUID | None = None
    # Links to other active devices; 0 for a device with no cable traced to anything.
    degree: int = 0


class TopologyLinkOut(BaseModel):
    """Two active devices joined by a cable, perhaps through sockets and patch panels ([via])."""

    a_id: uuid.UUID
    b_id: uuid.UUID
    # CABLE: traced over the cable plant; VIRTUAL: a virtual machine and its host.
    kind: Literal["CABLE", "VIRTUAL"] = "CABLE"
    a_port: str | None = None
    b_port: str | None = None
    via: list[str] = Field(default_factory=list)
    cable_types: list[str] = Field(default_factory=list)
    # Link state from the switch side; null when neither end is a switch.
    is_up: bool | None = None
    speed_mbps: int | None = None
    # The cables of the chain, from end A to end B; empty for a VIRTUAL link.
    connection_ids: list[uuid.UUID] = Field(default_factory=list)


class TopologyOut(BaseModel):
    nodes: list[TopologyNodeOut]
    links: list[TopologyLinkOut]


# --- faceplates ---------------------------------------------------------------------


class FaceplatePortIn(BaseModel):
    """Where a port lies on the panel drawing, as fractions of its width and height (top left corner)."""

    port_id: uuid.UUID
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    w: float = Field(gt=0, le=1)
    h: float = Field(gt=0, le=1)


class FaceplatePortsIn(BaseModel):
    ports: list[FaceplatePortIn] = Field(max_length=1000)


class FaceplatePortOut(FaceplatePortIn):
    port_name: str
    port_number: int


class FaceplateOut(BaseModel):
    device_id: uuid.UUID
    width: float
    height: float
    ports: list[FaceplatePortOut]
    updated_at: datetime


class FaceplateCopyIn(BaseModel):
    from_device_id: uuid.UUID


# --- device templates -----------------------------------------------------


class DeviceTemplateBase(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    device_type: DeviceType
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    port_count: int = Field(default=0, ge=0, le=96)
    port_prefix: str = Field(default="Port", max_length=30)
    port_speed_mbps: int = Field(default=1000, ge=10, le=400_000)
    rack_h: int | None = Field(default=None, ge=1, le=60)
    comment: str | None = Field(default=None, max_length=4000)


class DeviceTemplateCreate(DeviceTemplateBase):
    pass


class DeviceTemplateUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    device_type: DeviceType | None = None
    vendor: str | None = Field(default=None, max_length=50)
    model: str | None = Field(default=None, max_length=100)
    port_count: int | None = Field(default=None, ge=0, le=96)
    port_prefix: str | None = Field(default=None, max_length=30)
    port_speed_mbps: int | None = Field(default=None, ge=10, le=400_000)
    rack_h: int | None = Field(default=None, ge=1, le=60)
    comment: str | None = Field(default=None, max_length=4000)


class DeviceTemplateOut(ORM, DeviceTemplateBase):
    id: uuid.UUID


class DeviceFromTemplate(BaseModel):
    """Overrides for the device made from a template: the template fills the rest."""

    name: str = Field(min_length=1, max_length=100)
    floor_id: uuid.UUID | None = None
    x: float | None = None
    y: float | None = None
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address | None = None
    rack_id: uuid.UUID | None = None
    rack_u: int | None = Field(default=None, ge=1, le=60)
