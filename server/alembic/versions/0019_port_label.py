"""a free-text label on a port: where the cable goes (mainly the rear of a patch panel)

Revision ID: 0019
Revises: 0018
Create Date: 2026-11-13
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0019"
down_revision: str | None = "0018"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("ports", sa.Column("label", sa.String(length=120), nullable=True))


def downgrade() -> None:
    op.drop_column("ports", "label")
