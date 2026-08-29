"""Google identity verification, isolated behind a single entry point so the
provider can be swapped or mocked without touching router/business logic."""

from dataclasses import dataclass

from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.config import settings


class InvalidGoogleTokenError(Exception):
    pass


@dataclass(frozen=True)
class GoogleIdentity:
    subject_id: str
    email: str | None
    email_verified: bool


def verify_google_id_token(raw_id_token: str) -> GoogleIdentity:
    try:
        claims = google_id_token.verify_oauth2_token(
            raw_id_token,
            google_requests.Request(),
            audience=settings.google_web_client_id,
        )
    except ValueError as exc:
        raise InvalidGoogleTokenError(str(exc)) from exc

    subject_id = claims.get("sub")
    if not subject_id:
        raise InvalidGoogleTokenError("Google ID token is missing 'sub' claim")

    return GoogleIdentity(
        subject_id=subject_id,
        email=claims.get("email"),
        email_verified=bool(claims.get("email_verified", False)),
    )
