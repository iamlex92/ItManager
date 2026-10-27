"""device templates: saved presets for adding devices

Revision ID: 0014
Revises: 0013
Create Date: 2026-10-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "device_templates",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("name", sa.String(100), nullable=False, unique=True),
        sa.Column("device_type", sa.String(30), nullable=False),
        sa.Column("vendor", sa.String(50)),
        sa.Column("model", sa.String(100)),
        sa.Column("port_count", sa.Integer, nullable=False, server_default=sa.text("0")),
        sa.Column("port_prefix", sa.String(30), nullable=False, server_default=sa.text("'Port'")),
        sa.Column("port_speed_mbps", sa.Integer, nullable=False, server_default=sa.text("1000")),
        sa.Column("rack_h", sa.Integer),
        sa.Column("comment", sa.Text),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("device_templates")
