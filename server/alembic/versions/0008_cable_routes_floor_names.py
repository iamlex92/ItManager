"""cable routes (waypoints) and floor names

Revision ID: 0008
Revises: 0007
Create Date: 2026-10-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("connections", sa.Column("waypoints", JSONB(), server_default=sa.text("'[]'::jsonb"), nullable=False))
    op.add_column("floors", sa.Column("name", sa.String(100), nullable=True))


def downgrade() -> None:
    op.drop_column("floors", "name")
    op.drop_column("connections", "waypoints")
