"""custom scripts added in the web console

Revision ID: 0007
Revises: 0006
Create Date: 2026-10-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "custom_scripts",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("title", sa.String(100), nullable=False, unique=True),
        sa.Column("description", sa.Text(), server_default=sa.text("''"), nullable=False),
        sa.Column("shell", sa.String(20), server_default=sa.text("'powershell'"), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("params", JSONB(), server_default=sa.text("'[]'::jsonb"), nullable=False),
        sa.Column("critical", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("custom_scripts")
