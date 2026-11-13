"""device lifecycle fields, licenses with their computers, and other dated items

Revision ID: 0020
Revises: 0019
Create Date: 2026-11-13
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0020"
down_revision: str | None = "0019"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("serial_number", sa.String(length=100), nullable=True))
    op.add_column("devices", sa.Column("inventory_number", sa.String(length=100), nullable=True))
    op.add_column("devices", sa.Column("purchase_date", sa.Date(), nullable=True))
    op.add_column("devices", sa.Column("warranty_until", sa.Date(), nullable=True))
    op.add_column("devices", sa.Column("supplier", sa.String(length=100), nullable=True))
    op.add_column("devices", sa.Column("assigned_to", sa.String(length=100), nullable=True))
    op.create_table(
        "licenses",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("software", sa.String(length=120), nullable=False),
        sa.Column("vendor", sa.String(length=100), nullable=True),
        sa.Column("kind", sa.String(length=20), nullable=False, server_default="per_device"),
        sa.Column("seats", sa.Integer(), nullable=True),
        sa.Column("license_key", sa.Text(), nullable=True),
        sa.Column("valid_until", sa.Date(), nullable=True),
        sa.Column("purchased_on", sa.Date(), nullable=True),
        sa.Column("supplier", sa.String(length=100), nullable=True),
        sa.Column("contract", sa.String(length=100), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "license_devices",
        sa.Column(
            "license_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("licenses.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "device_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="CASCADE"),
            primary_key=True,
        ),
    )
    op.create_table(
        "deadlines",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("title", sa.String(length=150), nullable=False),
        sa.Column("kind", sa.String(length=20), nullable=False, server_default="other"),
        sa.Column("due_date", sa.Date(), nullable=False),
        sa.Column(
            "device_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
        ),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("is_done", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_deadlines_due", "deadlines", ["due_date"])


def downgrade() -> None:
    op.drop_index("ix_deadlines_due", table_name="deadlines")
    op.drop_table("deadlines")
    op.drop_table("license_devices")
    op.drop_table("licenses")
    for col in ("assigned_to", "supplier", "warranty_until", "purchase_date", "inventory_number", "serial_number"):
        op.drop_column("devices", col)
