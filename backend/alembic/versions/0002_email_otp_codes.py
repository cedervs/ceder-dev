"""email otp codes (Auth 2B)

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-29

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "email_otp_codes",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("email", sa.String(length=320), nullable=False),
        sa.Column("code_hash", sa.String(length=64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("attempt_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("request_ip", sa.String(length=45), nullable=True),
    )
    op.create_index("ix_email_otp_codes_email", "email_otp_codes", ["email"])
    op.create_index("ix_email_otp_codes_request_ip", "email_otp_codes", ["request_ip"])


def downgrade() -> None:
    op.drop_index("ix_email_otp_codes_request_ip", table_name="email_otp_codes")
    op.drop_index("ix_email_otp_codes_email", table_name="email_otp_codes")
    op.drop_table("email_otp_codes")
