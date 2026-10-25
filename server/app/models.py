import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, CIDR, INET, JSONB, MACADDR, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


def _uuid_pk() -> Mapped[uuid.UUID]:
    return mapped_column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))


class Floor(Base):
    __tablename__ = "floors"

    id: Mapped[uuid.UUID] = _uuid_pk()
    building_name: Mapped[str] = mapped_column(String(100))
    floor_number: Mapped[int] = mapped_column(Integer)
    plan_image_url: Mapped[str] = mapped_column(Text)
    scale_pixels_per_meter: Mapped[Decimal] = mapped_column(Numeric(10, 2), server_default=text("50.0"))
    # Not in the original spec: plan size so clients can lay out the canvas.
    width_px: Mapped[int | None] = mapped_column(Integer)
    height_px: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Vlan(Base):
    __tablename__ = "vlans"

    vlan_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)
    name: Mapped[str] = mapped_column(String(50))
    subnet_cidr: Mapped[Any] = mapped_column(CIDR)
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
    ssh_credential_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    snmp_community: Mapped[str | None] = mapped_column(String(50), server_default=text("'public'"))
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


class Connection(Base):
    __tablename__ = "connections"

    id: Mapped[uuid.UUID] = _uuid_pk()
    port_a_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("ports.id", ondelete="CASCADE"), index=True)
    port_b_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("ports.id", ondelete="CASCADE"), index=True)
    cable_type: Mapped[str] = mapped_column(String(20), server_default=text("'Cat5e'"))
    length_meters: Mapped[Decimal | None] = mapped_column(Numeric(6, 2))
    label: Mapped[str | None] = mapped_column(String(50))
    notes: Mapped[str | None] = mapped_column(Text)


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


class ScriptAuditLog(Base):
    __tablename__ = "script_audit_logs"

    id: Mapped[uuid.UUID] = _uuid_pk()
    script_name: Mapped[str] = mapped_column(String(100))
    triggered_by: Mapped[str] = mapped_column(String(100))
    parameters: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    executed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    exit_code: Mapped[int | None] = mapped_column(Integer)
    output_log: Mapped[str | None] = mapped_column(Text)
