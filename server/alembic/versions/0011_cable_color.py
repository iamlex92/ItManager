"""cable colour on the map

Revision ID: 0011
Revises: 0010
Create Date: 2026-10-27
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("connections", sa.Column("color", sa.String(7), nullable=True))


def downgrade() -> None:
    op.drop_column("connections", "color")
