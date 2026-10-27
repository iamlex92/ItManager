import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    LargeBinary,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, CIDR, INET, JSONB, MACADDR, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from .crypto import EncryptedText


class Base(DeclarativeBase):
    pass


def _uuid_pk() -> Mapped[uuid.UUID]:
    return mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))


class Floor(Base):
    __tablename__ = "floors"

    id: Mapped[uuid.UUID] = _uuid_pk()
    building_name: Mapped[str] = mapped_column(String(100))
    floor_number: Mapped[int] = mapped_column(Integer)
    # What people call the floor: «2 этаж», «Подвал», «Серверная». Empty: «Этаж N».
    name: Mapped[str | None] = mapped_column(String(100))
    plan_image_url: Mapped[str] = mapped_column(Text)
    scale_pixels_per_meter: Mapped[Decimal] = mapped_column(Numeric(10, 2), server_default=text("50.0"))
    # Not in the original spec: plan size so clients can lay out the canvas.
    width_px: Mapped[int | None] = mapped_column(Integer)
    height_px: Mapped[int | None] = mapped_column(Integer)
    # Where an uploaded plan image sits on the map, in map pixels. Null: it fills the whole floor.
    plan_x: Mapped[float | None] = mapped_column(Float)
    plan_y: Mapped[float | None] = mapped_column(Float)
    plan_w: Mapped[float | None] = mapped_column(Float)
    plan_h: Mapped[float | None] = mapped_column(Float)
    # Free lines drawn over the map in the app: [{"color": "#RRGGBB", "width": 4, "points": [x, y, ...]}].
    drawings: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    # Zones outlined on the map: [{"id": "z1", "name": "Серверная", "is_room": true, "points": [x, y, ...]}].
    # A device whose icon lies inside a room zone belongs to that room.
    zones: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class FloorPlanImage(Base):
    """A plan uploaded from the app (PNG, JPEG or SVG), served back at /floors/{id}/plan-image."""

    __tablename__ = "floor_plan_images"

    floor_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("floors.id", ondelete="CASCADE"), primary_key=True)
    content_type: Mapped[str] = mapped_column(String(50))
    data: Mapped[bytes] = mapped_column(LargeBinary)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class Vlan(Base):
    __tablename__ = "vlans"

    vlan_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)
    name: Mapped[str] = mapped_column(String(50))
    # Empty for a VLAN taken from a switch config that has no address in it.
    subnet_cidr: Mapped[Any | None] = mapped_column(CIDR)
    color_hex: Mapped[str] = mapped_column(String(7), server_default=text("'#3498db'"))
    description: Mapped[str | None] = mapped_column(Text)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[uuid.UUID] = _uuid_pk()
    floor_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("floors.id", ondelete="SET NULL"), index=True)
    name: Mapped[str] = mapped_column(String(100))
    vendor: Mapped[str | None] = mapped_column(String(50))
    model: Mapped[str | None] = mapped_column(String(100))
    device_type: Mapped[str] = mapped_column(String(30))
    pos_x: Mapped[Decimal | None] = mapped_column(Numeric(10, 2))
    pos_y: Mapped[Decimal | None] = mapped_column(Numeric(10, 2))
    ip_address: Mapped[Any | None] = mapped_column(INET)
    mac_address: Mapped[str | None] = mapped_column(MACADDR)
    nfc_tag_uid: Mapped[str | None] = mapped_column(String(50), unique=True)
    ssh_credential_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("credentials.id", ondelete="SET NULL"))
    # For a virtual machine: the server it runs on. Not a cable, so it never shows on L1.
    host_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"))
    # Free-text note shown in the device card.
    comment: Mapped[str | None] = mapped_column(Text)
    # For a device mounted in a rack: the rack (a device of type RACK), its lowest unit (1 is the bottom)
    # and how many units it takes. For the rack itself: rack_units is its height.
    rack_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("devices.id", ondelete="SET NULL"))
    rack_u: Mapped[int | None] = mapped_column(Integer)
    rack_h: Mapped[int | None] = mapped_column(Integer)
    rack_units: Mapped[int | None] = mapped_column(Integer)
    # Encrypted at rest when SECRET_KEY is set. The Python-side default goes through the
    # encryption; the server default only covers rows inserted outside the app.
    snmp_community: Mapped[str | None] = mapped_column(EncryptedText, default="public", server_default=text("'public'"))
    is_active: Mapped[bool] = mapped_column(Boolean, server_default=text("true"))
    # Not in the original spec: last known reachability, filled by the poller.
    is_online: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # "metadata" is reserved on declarative classes, hence the trailing underscore.
    metadata_: Mapped[dict[str, Any]] = mapped_column("metadata", JSONB, server_default=text("'{}'::jsonb"))

    ports: Mapped[list["Port"]] = relationship(
        back_populates="device",
        order_by="Port.port_number",
        cascade="all, delete-orphan",
        passive_deletes=True,
    )


class Port(Base):
    __tablename__ = "ports"
    __table_args__ = (UniqueConstraint("device_id", "port_number"),)

    id: Mapped[uuid.UUID] = _uuid_pk()
    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    port_number: Mapped[int] = mapped_column(Integer)
    port_name: Mapped[str] = mapped_column(String(50))
    is_uplink: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    mode: Mapped[str] = mapped_column(String(10), server_default=text("'ACCESS'"))
    access_vlan: Mapped[int | None] = mapped_column(ForeignKey("vlans.vlan_id"))
    tagged_vlans: Mapped[list[int]] = mapped_column(ARRAY(Integer), server_default=text("'{}'"))
    is_admin_up: Mapped[bool] = mapped_column(Boolean, server_default=text("true"))
    is_oper_up: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    speed_mbps: Mapped[int] = mapped_column(Integer, server_default=text("1000"))
    poe_enabled: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    connected_mac: Mapped[str | None] = mapped_column(MACADDR)
    # Not in the original spec: IF-MIB ifIndex, to map SNMP traps to ports.
    if_index: Mapped[int | None] = mapped_column(Integer)

    device: Mapped[Device] = relationship(back_populates="ports")


class PortTraffic(Base):
    """Average port throughput over one sampling interval, bits per second."""

    __tablename__ = "port_traffic"

    port_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("ports.id", ondelete="CASCADE"), primary_key=True)
    sampled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), primary_key=True, index=True)
    in_bps: Mapped[int] = mapped_column(BigInteger)
    out_bps: Mapped[int] = mapped_column(BigInteger)


class Connection(Base):
    __tablename__ = "connections"

    id: Mapped[uuid.UUID] = _uuid_pk()
    port_a_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("ports.id", ondelete="CASCADE"), index=True)
    port_b_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("ports.id", ondelete="CASCADE"), index=True)
    cable_type: Mapped[str] = mapped_column(String(20), server_default=text("'Cat5e'"))
    length_meters: Mapped[Decimal | None] = mapped_column(Numeric(6, 2))
    label: Mapped[str | None] = mapped_column(String(50))
    notes: Mapped[str | None] = mapped_column(Text)
    # The route drawn on the map between the two ends: [{"x": 1.0, "y": 2.0, "floor_id": "..."}].
    # A point without floor_id lies on the floor of end A. Empty: a straight line.
    waypoints: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    # The colour of the cable on the map, "#RRGGBB". Null: the map picks one by cable type and layer.
    color: Mapped[str | None] = mapped_column(String(7))


class DeviceConfig(Base):
    __tablename__ = "device_configs"

    id: Mapped[uuid.UUID] = _uuid_pk()
    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    config_type: Mapped[str] = mapped_column(String(20), server_default=text("'RUNNING'"))
    content: Mapped[str] = mapped_column(Text)
    config_hash: Mapped[str] = mapped_column(String(64))
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    triggered_by: Mapped[str] = mapped_column(String(50), server_default=text("'SCHEDULED_BACKUP'"))


class DeviceAlert(Base):
    __tablename__ = "device_alerts"

    id: Mapped[uuid.UUID] = _uuid_pk()
    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    port_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("ports.id", ondelete="SET NULL"))
    severity: Mapped[str] = mapped_column(String(20))
    alert_type: Mapped[str] = mapped_column(String(50))
    message: Mapped[str] = mapped_column(Text)
    is_resolved: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Credential(Base):
    """A login for equipment or services (SSH, SNMPv3, WinRM). The secret is encrypted at rest
    and write-only through the API."""

    __tablename__ = "credentials"

    id: Mapped[uuid.UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(100), unique=True)
    kind: Mapped[str] = mapped_column(String(20))
    username: Mapped[str | None] = mapped_column(String(100))
    secret: Mapped[str | None] = mapped_column(EncryptedText)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class CustomScript(Base):
    """A script added by the IT team in the web console. The phone lists it with the runbooks;
    runs are emulated (the text with parameters filled in is shown, nothing is executed)."""

    __tablename__ = "custom_scripts"

    id: Mapped[uuid.UUID] = _uuid_pk()
    title: Mapped[str] = mapped_column(String(100), unique=True)
    description: Mapped[str] = mapped_column(Text, server_default=text("''"))
    shell: Mapped[str] = mapped_column(String(20), server_default=text("'powershell'"))
    body: Mapped[str] = mapped_column(Text)
    # [{"name": "account", "label": "Учётная запись", "required": true, "placeholder": "i.ivanov"}]
    params: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    critical: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class ScriptAuditLog(Base):
    __tablename__ = "script_audit_logs"

    id: Mapped[uuid.UUID] = _uuid_pk()
    script_name: Mapped[str] = mapped_column(String(100))
    triggered_by: Mapped[str] = mapped_column(String(100))
    parameters: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    executed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    exit_code: Mapped[int | None] = mapped_column(Integer)
    output_log: Mapped[str | None] = mapped_column(Text)
    # Runbooks keep stderr apart from stdout; switch writes leave it empty.
    stderr_log: Mapped[str | None] = mapped_column(Text)
    duration_ms: Mapped[int | None] = mapped_column(Integer)


class Peripheral(Base):
    """Equipment without a network port that belongs to a workplace: a monitor, a keyboard, a docking
    station. Attached to a device (usually a computer) or kept in stock when device_id is empty."""

    __tablename__ = "peripherals"

    id: Mapped[uuid.UUID] = _uuid_pk()
    device_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("devices.id", ondelete="SET NULL"), index=True
    )
    kind: Mapped[str] = mapped_column(String(30))
    vendor: Mapped[str | None] = mapped_column(String(50))
    model: Mapped[str | None] = mapped_column(String(100))
    # What matters about it in a few words: «24", 1920×1080, DP/HDMI».
    specs: Mapped[str | None] = mapped_column(String(200))
    serial_number: Mapped[str | None] = mapped_column(String(100))
    inventory_number: Mapped[str | None] = mapped_column(String(100))
    comment: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class DeviceFaceplate(Base):
    """A drawing of a switch's front panel (SVG) and where each of its ports lies on it.

    Port places are fractions of the drawing: {"port_id": "...", "x": 0.1, "y": 0.2, "w": 0.02, "h": 0.1},
    x and y the top left corner, so the marks follow the drawing at any size."""

    __tablename__ = "device_faceplates"

    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), primary_key=True)
    svg: Mapped[str] = mapped_column(Text)
    width: Mapped[float] = mapped_column(Float)
    height: Mapped[float] = mapped_column(Float)
    ports: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
