import uuid
from collections.abc import Callable
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth.google import GoogleIdentity, InvalidGoogleTokenError
from app.db import get_db
from app.deps import get_google_verifier
from app.models import AuthIdentity, RefreshToken, User
from app.schemas import GoogleAuthRequest, LogoutRequest, RefreshRequest, TokenResponse
from app.security import create_access_token, generate_refresh_token, hash_refresh_token, refresh_token_expiry

router = APIRouter(prefix="/v1/auth", tags=["auth"])

GOOGLE_PROVIDER = "google"


def _issue_tokens(db: Session, user: User, family_id: uuid.UUID, device_info: str | None) -> TokenResponse:
    access_token, expires_in = create_access_token(user.id)
    raw_refresh_token, refresh_token_hash = generate_refresh_token()

    db.add(
        RefreshToken(
            id=uuid.uuid4(),
            user_id=user.id,
            family_id=family_id,
            token_hash=refresh_token_hash,
            device_info=device_info,
            expires_at=refresh_token_expiry(),
        )
    )
    db.commit()

    return TokenResponse(access_token=access_token, refresh_token=raw_refresh_token, expires_in=expires_in)


@router.post("/google", response_model=TokenResponse)
def login_with_google(
    payload: GoogleAuthRequest,
    db: Session = Depends(get_db),
    verify_google_token: Callable[[str], GoogleIdentity] = Depends(get_google_verifier),
) -> TokenResponse:
    try:
        identity = verify_google_token(payload.id_token)
    except InvalidGoogleTokenError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_google_token") from exc

    auth_identity = db.scalar(
        select(AuthIdentity).where(
            AuthIdentity.provider == GOOGLE_PROVIDER,
            AuthIdentity.provider_subject_id == identity.subject_id,
        )
    )

    if auth_identity is not None:
        user = auth_identity.user
    else:
        user = User(id=uuid.uuid4())
        db.add(user)
        db.flush()
        db.add(
            AuthIdentity(
                id=uuid.uuid4(),
                user_id=user.id,
                provider=GOOGLE_PROVIDER,
                provider_subject_id=identity.subject_id,
                email=identity.email,
                verified_at=datetime.now(timezone.utc) if identity.email_verified else None,
            )
        )
        db.commit()

    return _issue_tokens(db, user, family_id=uuid.uuid4(), device_info=payload.device_info)


@router.post("/refresh", response_model=TokenResponse)
def refresh(payload: RefreshRequest, db: Session = Depends(get_db)) -> TokenResponse:
    token_hash = hash_refresh_token(payload.refresh_token)
    stored = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_hash))

    if stored is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_refresh_token")

    if stored.revoked_at is not None:
        # Reuse of an already-rotated/revoked token: treat the whole family as
        # compromised and revoke every still-active token in it.
        db.execute(
            RefreshToken.__table__.update()
            .where(RefreshToken.family_id == stored.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=datetime.now(timezone.utc))
        )
        db.commit()
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="refresh_token_reused")

    if stored.expires_at < datetime.now(timezone.utc):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="refresh_token_expired")

    user = db.get(User, stored.user_id)

    new_tokens = _issue_tokens(db, user, family_id=stored.family_id, device_info=stored.device_info)

    new_token_row = db.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(new_tokens.refresh_token))
    )
    stored.revoked_at = datetime.now(timezone.utc)
    stored.replaced_by_id = new_token_row.id
    db.commit()

    return new_tokens


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(payload: LogoutRequest, db: Session = Depends(get_db)) -> None:
    token_hash = hash_refresh_token(payload.refresh_token)
    stored = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_hash))

    if stored is not None and stored.revoked_at is None:
        stored.revoked_at = datetime.now(timezone.utc)
        db.commit()
