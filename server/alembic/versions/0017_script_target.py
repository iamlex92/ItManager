"""where a custom script runs: only shown (emulator), on a domain computer, or on the NetAdmin server

Revision ID: 0017
Revises: 0016
Create Date: 2026-10-30
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0017"
down_revision: str | None = "0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Scripts written before keep what they did: shown, never run.
    op.add_column(
        "custom_scripts", sa.Column("runs_on", sa.String(20), server_default=sa.text("'emulator'"), nullable=False)
    )


def downgrade() -> None:
    op.drop_column("custom_scripts", "runs_on")
