"""script_audit_logs: stderr and duration for runbook runs

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("script_audit_logs", sa.Column("stderr_log", sa.Text(), nullable=True))
    op.add_column("script_audit_logs", sa.Column("duration_ms", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("script_audit_logs", "duration_ms")
    op.drop_column("script_audit_logs", "stderr_log")
