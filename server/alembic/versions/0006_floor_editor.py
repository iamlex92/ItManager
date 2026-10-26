"""floor editor: an uploaded plan image, its placement on the map, and free drawings

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-26
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    for name in ("plan_x", "plan_y", "plan_w", "plan_h"):
        op.add_column("floors", sa.Column(name, sa.Float(), nullable=True))
    op.add_column("floors", sa.Column("drawings", JSONB(), server_default=sa.text("'[]'::jsonb"), nullable=False))
    # In the database rather than on disk, so the built-in backups carry the plans too.
    op.create_table(
        "floor_plan_images",
        sa.Column("floor_id", UUID(as_uuid=True), sa.ForeignKey("floors.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("content_type", sa.String(50), nullable=False),
        sa.Column("data", sa.LargeBinary(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("floor_plan_images")
    op.drop_column("floors", "drawings")
    for name in ("plan_h", "plan_w", "plan_y", "plan_x"):
        op.drop_column("floors", name)
