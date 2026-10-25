"""port_traffic: sampled port throughput for utilization charts

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "port_traffic",
        sa.Column("port_id", UUID(as_uuid=True), sa.ForeignKey("ports.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("sampled_at", sa.DateTime(timezone=True), primary_key=True),
        sa.Column("in_bps", sa.BigInteger(), nullable=False),
        sa.Column("out_bps", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_port_traffic_sampled_at", "port_traffic", ["sampled_at"])


def downgrade() -> None:
    op.drop_index("ix_port_traffic_sampled_at", table_name="port_traffic")
    op.drop_table("port_traffic")
