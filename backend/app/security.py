import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone

import jwt

from app.config import settings

JWT_ALGORITHM = "HS256"


def create_access_token(user_id: uuid.UUID) -> tuple[str, int]:
    ttl_seconds = settings.access_token_ttl_minutes * 60
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "type": "access",
        "jti": str(uuid.uuid4()),
        "iat": now,
        "exp": now + timedelta(seconds=ttl_seconds),
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm=JWT_ALGORITHM)
    return token, ttl_seconds


def decode_access_token(token: str) -> dict:
    return jwt.decode(token, settings.jwt_secret, algorithms=[JWT_ALGORITHM])


def generate_refresh_token() -> tuple[str, str]:
    """Returns (raw_token_to_send_to_client, hash_to_store_in_db)."""
    raw = secrets.token_urlsafe(48)
    return raw, hash_refresh_token(raw)


def hash_refresh_token(raw_token: str) -> str:
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


def refresh_token_expiry() -> datetime:
    return datetime.now(timezone.utc) + timedelta(days=settings.refresh_token_ttl_days)
