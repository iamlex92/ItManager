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
]
PortMode = Literal["ACCESS", "TRUNK"]
Severity = Literal["INFO", "WARNING", "CRITICAL"]

_MAC_RE = r"^[0-9a-fA-F]{2}([:-]?[0-9a-fA-F]{2}){5}$"


def _norm_mac(v: str | None) -> str | None:
    if v is None:
        return None
    raw = v.replace(":", "").replace("-", "").replace(".", "").lower()
    return ":".join(raw[i : i + 2] for i in range(0, 12, 2))


class ORM(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# --- floors ---------------------------------------------------------------


class FloorBase(BaseModel):
    building_name: str = Field(max_length=100)
    floor_number: int
    plan_image_url: str
    scale_pixels_per_meter: float = 50.0
    width_px: int | None = None
    height_px: int | None = None


class FloorCreate(FloorBase):
    pass


class FloorUpdate(BaseModel):
    building_name: str | None = Field(default=None, max_length=100)
    floor_number: int | None = None
    plan_image_url: str | None = None
    scale_pixels_per_meter: float | None = None
    width_px: int | None = None
    height_px: int | None = None


class FloorOut(ORM, FloorBase):
    id: uuid.UUID
    created_at: datetime


# --- vlans ----------------------------------------------------------------


class VlanBase(BaseModel):
    name: str = Field(max_length=50)
    subnet_cidr: ipaddress.IPv4Network | ipaddress.IPv6Network
    color_hex: str = Field(default="#3498db", pattern=r"^#[0-9a-fA-F]{6}$")
    description: str | None = None

    @field_serializer("subnet_cidr")
    def _ser_cidr(self, v: Any) -> str:
        return str(v)


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

    @field_validator("mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)


class DeviceCreate(DeviceBase):
    pass


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
    is_active: bool | None = None
    metadata: dict[str, Any] | None = None

    @field_validator("mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)


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
    snmp_community: str | None
    metadata: dict[str, Any]


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

    @field_validator("connected_mac")
    @classmethod
    def _mac(cls, v: str | None) -> str | None:
        return _norm_mac(v)


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


class ConnectionCreate(BaseModel):
    port_a_id: uuid.UUID
    port_b_id: uuid.UUID
    cable_type: str = Field(default="Cat5e", max_length=20)
    length_meters: float | None = None
    label: str | None = Field(default=None, max_length=50)
    notes: str | None = None


class ConnectionUpdate(BaseModel):
    cable_type: str | None = Field(default=None, max_length=20)
    length_meters: float | None = None
    label: str | None = Field(default=None, max_length=50)
    notes: str | None = None


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


class Consumable(BaseModel):
    name: str
    color_hex: str
    level_percent: int


class PrinterOut(BaseModel):
    id: uuid.UUID
    name: str
    ip: str | None
    model: str | None
    is_online: bool
    total_pages: int | None
    consumables: list[Consumable]


# --- misc -----------------------------------------------------------------


class HealthOut(BaseModel):
    status: str
    version: str
    hal_mode: str
    device_writes_enabled: bool


class ErrorCode(BaseModel):
    code: str
    message: str
