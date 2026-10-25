"""credentials table (encrypted secrets); devices.snmp_community as text for ciphertext

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-25
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "credentials",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("name", sa.String(100), nullable=False, unique=True),
        sa.Column("kind", sa.String(20), nullable=False),
        sa.Column("username", sa.String(100), nullable=True),
        # Encrypted with SECRET_KEY ("enc:v1:..."), never returned by the API.
        sa.Column("secret", sa.Text(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.alter_column("devices", "snmp_community", type_=sa.Text(), existing_type=sa.String(50))
    op.execute("UPDATE devices SET ssh_credential_id = NULL")
    op.create_foreign_key(
        "devices_ssh_credential_id_fkey", "devices", "credentials", ["ssh_credential_id"], ["id"], ondelete="SET NULL"
    )


def downgrade() -> None:
    op.drop_constraint("devices_ssh_credential_id_fkey", "devices", type_="foreignkey")
    # Encrypted communities do not fit back into 50 characters; reset them.
    op.execute("UPDATE devices SET snmp_community = 'public' WHERE snmp_community LIKE 'enc:%'")
    op.alter_column("devices", "snmp_community", type_=sa.String(50), existing_type=sa.Text())
    op.drop_table("credentials")
