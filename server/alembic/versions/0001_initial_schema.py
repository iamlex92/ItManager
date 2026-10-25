"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-10-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "floors",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("building_name", sa.String(length=100), nullable=False),
        sa.Column("floor_number", sa.Integer(), nullable=False),
        sa.Column("plan_image_url", sa.Text(), nullable=False),
        sa.Column(
            "scale_pixels_per_meter", sa.Numeric(precision=10, scale=2), server_default=sa.text("50.0"), nullable=False
        ),
        sa.Column("width_px", sa.Integer(), nullable=True),
        sa.Column("height_px", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "script_audit_logs",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("script_name", sa.String(length=100), nullable=False),
        sa.Column("triggered_by", sa.String(length=100), nullable=False),
        sa.Column("parameters", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("executed_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("exit_code", sa.Integer(), nullable=True),
        sa.Column("output_log", sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_table(
        "vlans",
        sa.Column("vlan_id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("name", sa.String(length=50), nullable=False),
        sa.Column("subnet_cidr", postgresql.CIDR(), nullable=False),
        sa.Column("color_hex", sa.String(length=7), server_default=sa.text("'#3498db'"), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint("vlan_id"),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("floor_id", sa.UUID(), nullable=True),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("vendor", sa.String(length=50), nullable=True),
        sa.Column("model", sa.String(length=100), nullable=True),
        sa.Column("device_type", sa.String(length=30), nullable=False),
        sa.Column("pos_x", sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column("pos_y", sa.Numeric(precision=10, scale=2), nullable=True),
        sa.Column("ip_address", postgresql.INET(), nullable=True),
        sa.Column("mac_address", postgresql.MACADDR(), nullable=True),
        sa.Column("nfc_tag_uid", sa.String(length=50), nullable=True),
        sa.Column("ssh_credential_id", sa.UUID(), nullable=True),
        sa.Column("snmp_community", sa.String(length=50), server_default=sa.text("'public'"), nullable=True),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("is_online", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "metadata", postgresql.JSONB(astext_type=sa.Text()), server_default=sa.text("'{}'::jsonb"), nullable=False
        ),
        sa.ForeignKeyConstraint(["floor_id"], ["floors.id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("nfc_tag_uid"),
    )
    op.create_index(op.f("ix_devices_floor_id"), "devices", ["floor_id"], unique=False)
    op.create_table(
        "device_configs",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("device_id", sa.UUID(), nullable=False),
        sa.Column("config_type", sa.String(length=20), server_default=sa.text("'RUNNING'"), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("config_hash", sa.String(length=64), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("triggered_by", sa.String(length=50), server_default=sa.text("'SCHEDULED_BACKUP'"), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["devices.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_device_configs_device_id"), "device_configs", ["device_id"], unique=False)
    op.create_table(
        "ports",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("device_id", sa.UUID(), nullable=False),
        sa.Column("port_number", sa.Integer(), nullable=False),
        sa.Column("port_name", sa.String(length=50), nullable=False),
        sa.Column("is_uplink", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("mode", sa.String(length=10), server_default=sa.text("'ACCESS'"), nullable=False),
        sa.Column("access_vlan", sa.Integer(), nullable=True),
        sa.Column("tagged_vlans", postgresql.ARRAY(sa.Integer()), server_default=sa.text("'{}'"), nullable=False),
        sa.Column("is_admin_up", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("is_oper_up", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("speed_mbps", sa.Integer(), server_default=sa.text("1000"), nullable=False),
        sa.Column("poe_enabled", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("connected_mac", postgresql.MACADDR(), nullable=True),
        sa.ForeignKeyConstraint(
            ["access_vlan"],
            ["vlans.vlan_id"],
        ),
        sa.ForeignKeyConstraint(["device_id"], ["devices.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("device_id", "port_number"),
    )
    op.create_index(op.f("ix_ports_device_id"), "ports", ["device_id"], unique=False)
    op.create_table(
        "connections",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("port_a_id", sa.UUID(), nullable=False),
        sa.Column("port_b_id", sa.UUID(), nullable=False),
        sa.Column("cable_type", sa.String(length=20), server_default=sa.text("'Cat5e'"), nullable=False),
        sa.Column("length_meters", sa.Numeric(precision=6, scale=2), nullable=True),
        sa.Column("label", sa.String(length=50), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(["port_a_id"], ["ports.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["port_b_id"], ["ports.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_connections_port_a_id"), "connections", ["port_a_id"], unique=False)
    op.create_index(op.f("ix_connections_port_b_id"), "connections", ["port_b_id"], unique=False)
    op.create_table(
        "device_alerts",
        sa.Column("id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("device_id", sa.UUID(), nullable=False),
        sa.Column("port_id", sa.UUID(), nullable=True),
        sa.Column("severity", sa.String(length=20), nullable=False),
        sa.Column("alert_type", sa.String(length=50), nullable=False),
        sa.Column("message", sa.Text(), nullable=False),
        sa.Column("is_resolved", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["device_id"], ["devices.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["port_id"], ["ports.id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_device_alerts_device_id"), "device_alerts", ["device_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_device_alerts_device_id"), table_name="device_alerts")
    op.drop_table("device_alerts")
    op.drop_index(op.f("ix_connections_port_b_id"), table_name="connections")
    op.drop_index(op.f("ix_connections_port_a_id"), table_name="connections")
    op.drop_table("connections")
    op.drop_index(op.f("ix_ports_device_id"), table_name="ports")
    op.drop_table("ports")
    op.drop_index(op.f("ix_device_configs_device_id"), table_name="device_configs")
    op.drop_table("device_configs")
    op.drop_index(op.f("ix_devices_floor_id"), table_name="devices")
    op.drop_table("devices")
    op.drop_table("vlans")
    op.drop_table("script_audit_logs")
    op.drop_table("floors")
