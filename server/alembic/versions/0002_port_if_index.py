"""ports.if_index for mapping SNMP traps to ports

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("ports", sa.Column("if_index", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("ports", "if_index")
