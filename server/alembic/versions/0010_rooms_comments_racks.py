"""zones on floors, device comment, racks

Revision ID: 0010
Revises: 0009
Create Date: 2026-10-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision: str = "0010"
down_revision: str | None = "0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("floors", sa.Column("zones", JSONB, server_default=sa.text("'[]'::jsonb"), nullable=False))
    op.add_column("devices", sa.Column("comment", sa.Text(), nullable=True))
    op.add_column(
        "devices",
        sa.Column("rack_id", UUID(as_uuid=True), sa.ForeignKey("devices.id", ondelete="SET NULL"), nullable=True),
    )
    op.add_column("devices", sa.Column("rack_u", sa.Integer(), nullable=True))
    op.add_column("devices", sa.Column("rack_h", sa.Integer(), nullable=True))
    op.add_column("devices", sa.Column("rack_units", sa.Integer(), nullable=True))


def downgrade() -> None:
    for column in ("rack_units", "rack_h", "rack_u", "rack_id", "comment"):
        op.drop_column("devices", column)
    op.drop_column("floors", "zones")
