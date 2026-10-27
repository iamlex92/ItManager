"""a VLAN may have no subnet: VLANs added from a switch config often have no address

Revision ID: 0013
Revises: 0012
Create Date: 2026-10-27
"""

from collections.abc import Sequence

from alembic import op
from sqlalchemy.dialects.postgresql import CIDR

revision: str = "0013"
down_revision: str | None = "0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column("vlans", "subnet_cidr", existing_type=CIDR(), nullable=True)


def downgrade() -> None:
    # Back to required: a placeholder for VLANs that have none.
    op.execute("UPDATE vlans SET subnet_cidr = '0.0.0.0/32' WHERE subnet_cidr IS NULL")
    op.alter_column("vlans", "subnet_cidr", existing_type=CIDR(), nullable=False)
