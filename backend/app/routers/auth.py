import uuid
from collections.abc import Callable
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth.email_otp import generate_otp_code, hash_otp_code, render_otp_email, verify_otp_code
from app.auth.email_sender import EmailSender
from app.auth.google import GoogleIdentity, InvalidGoogleTokenError
from app.config import settings
from app.db import get_db
from app.deps import get_email_sender, get_google_verifier
from app.models import AuthIdentity, EmailOtpCode, RefreshToken, User
from app.schemas import (
    GoogleAuthRequest,
    LogoutRequest,
    RefreshRequest,
    RequestEmailCodeRequest,
    TokenResponse,
    VerifyEmailCodeRequest,
)
from app.security import create_access_token, generate_refresh_token, hash_refresh_token, refresh_token_expiry

router = APIRouter(prefix="/v1/auth", tags=["auth"])

GOOGLE_PROVIDER = "google"
EMAIL_OTP_PROVIDER = "email_otp"


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

    # Normalized the same way as the email_otp path, so a string comparison between the
    # two providers' stored emails is meaningful for _find_or_create_user_for_verified_email.
    normalized_email = identity.email.lower().strip() if identity.email else None
    currently_verified = bool(normalized_email and identity.email_verified)

    if auth_identity is not None:
        user = auth_identity.user
        # Refresh from *this* login's Google claims every time, never from stale data.
        # currently_verified=False (no verified email on this login) clears verified_at
        # so whatever email is on file — old or current — stops counting as proof for a
        # *new* OTP linking, without touching provider_subject_id, user_id, or any
        # already-linked identity. See _find_or_create_user_for_verified_email below.
        if currently_verified:
            auth_identity.email = normalized_email
            auth_identity.verified_at = datetime.now(timezone.utc)
        else:
            auth_identity.verified_at = None
        db.commit()
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
                email=normalized_email,
                verified_at=datetime.now(timezone.utc) if currently_verified else None,
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


@router.post("/email/request-code", status_code=status.HTTP_202_ACCEPTED)
def request_email_code(
    payload: RequestEmailCodeRequest,
    request: Request,
    db: Session = Depends(get_db),
    email_sender: EmailSender = Depends(get_email_sender),
) -> None:
    email = payload.email.lower().strip()
    client_ip = request.client.host if request.client else None
    now = datetime.now(timezone.utc)
    window_start = now - timedelta(hours=1)

    last_code = db.scalar(
        select(EmailOtpCode).where(EmailOtpCode.email == email).order_by(EmailOtpCode.created_at.desc()).limit(1)
    )
    if last_code is not None and last_code.created_at > now - timedelta(seconds=settings.otp_resend_cooldown_seconds):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="resend_cooldown")

    requests_last_hour = db.scalar(
        select(func.count())
        .select_from(EmailOtpCode)
        .where(EmailOtpCode.email == email, EmailOtpCode.created_at > window_start)
    )
    if requests_last_hour >= settings.otp_max_requests_per_email_per_hour:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="too_many_requests")

    if client_ip is not None:
        ip_requests_last_hour = db.scalar(
            select(func.count())
            .select_from(EmailOtpCode)
            .where(EmailOtpCode.request_ip == client_ip, EmailOtpCode.created_at > window_start)
        )
        if ip_requests_last_hour >= settings.otp_max_requests_per_ip_per_hour:
            raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="too_many_requests")

    # Only one "live" (usable) code per email at a time — a new request supersedes
    # whatever code preceded it, whether or not that one was ever consumed.
    db.execute(
        EmailOtpCode.__table__.update()
        .where(EmailOtpCode.email == email, EmailOtpCode.consumed_at.is_(None))
        .values(consumed_at=now)
    )

    code = generate_otp_code()
    db.add(
        EmailOtpCode(
            id=uuid.uuid4(),
            email=email,
            code_hash=hash_otp_code(email, code),
            expires_at=now + timedelta(minutes=settings.otp_ttl_minutes),
            request_ip=client_ip,
        )
    )
    db.commit()

    subject, body = render_otp_email(code, payload.locale)
    email_sender.send(to=email, subject=subject, body=body)


def _find_or_create_user_for_verified_email(db: Session, email: str) -> User:
    """Auth 2B account-linking rule (validated 2026-08-29) — do not change this behavior
    silently; see docs/architecture.md §7 and the tests in test_auth_email_otp.py for the
    cases this must keep satisfying.

    1. If an email_otp AuthIdentity already exists for this email, reuse its user — never
       creates a second account for the same verified email address.
    2. Else, if a Google AuthIdentity exists for this *exact* email AND that identity's
       email was itself verified by Google (verified_at is set), attach the new email_otp
       identity to that SAME user. Both providers independently vouch for control of the
       mailbox, so this is not a silent merge of two accounts — it is recognizing one
       already-existing account by a second proof of the same mailbox.
    3. Else, create a brand new user.

    This function NEVER merges two users that already each have their own identities/data.
    Merging two pre-existing distinct accounts is explicitly out of scope and must go
    through a separate, explicit recovery/merge mechanism later — never automatically here.

    Invariant this relies on (enforced in login_with_google, not here): a Google
    AuthIdentity's (email, verified_at) always reflects *that identity's most recent
    login*, refreshed every time — never a stale email frozen at first signup. If the
    Google account's verified email changes, the old email stops matching this query on
    the very next Google login (the row's `email` column is overwritten), and any login
    without a verified email clears `verified_at` so the identity can no longer be used
    as linking proof until Google vouches for an email again. This is what stops an
    email that used to belong to one Google account, but was reassigned to someone else,
    from silently qualifying as linking proof for the new owner.
    """
    existing = db.scalar(
        select(AuthIdentity).where(
            AuthIdentity.provider == EMAIL_OTP_PROVIDER,
            AuthIdentity.provider_subject_id == email,
        )
    )
    if existing is not None:
        return existing.user

    verified_google_identity = db.scalar(
        select(AuthIdentity).where(
            AuthIdentity.provider == GOOGLE_PROVIDER,
            AuthIdentity.email == email,
            AuthIdentity.verified_at.is_not(None),
        )
    )
    if verified_google_identity is not None:
        user = verified_google_identity.user
    else:
        user = User(id=uuid.uuid4())
        db.add(user)
        db.flush()

    db.add(
        AuthIdentity(
            id=uuid.uuid4(),
            user_id=user.id,
            provider=EMAIL_OTP_PROVIDER,
            provider_subject_id=email,
            email=email,
            verified_at=datetime.now(timezone.utc),
        )
    )
    return user


@router.post("/email/verify-code", response_model=TokenResponse)
def verify_email_code(payload: VerifyEmailCodeRequest, db: Session = Depends(get_db)) -> TokenResponse:
    email = payload.email.lower().strip()

    otp_row = db.scalar(
        select(EmailOtpCode)
        .where(EmailOtpCode.email == email, EmailOtpCode.consumed_at.is_(None))
        .order_by(EmailOtpCode.created_at.desc())
        .limit(1)
    )

    if otp_row is None or otp_row.expires_at < datetime.now(timezone.utc):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_code")

    if otp_row.attempt_count >= settings.otp_max_attempts:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="too_many_attempts")

    if not verify_otp_code(email, payload.code, otp_row.code_hash):
        otp_row.attempt_count += 1
        db.commit()
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_code")

    otp_row_id = otp_row.id
    otp_row.consumed_at = datetime.now(timezone.utc)

    try:
        user = _find_or_create_user_for_verified_email(db, email)
        db.commit()
    except IntegrityError:
        db.rollback()
        # Another concurrent request for the same email won the identity-creation race
        # (unique constraint on auth_identities(provider, provider_subject_id)) — reuse
        # its result rather than erroring or creating a duplicate.
        existing = db.scalar(
            select(AuthIdentity).where(
                AuthIdentity.provider == EMAIL_OTP_PROVIDER,
                AuthIdentity.provider_subject_id == email,
            )
        )
        if existing is None:
            raise
        user = existing.user
        # The consumed_at update above was rolled back together with the failed insert —
        # reapply it so this code still cannot be replayed.
        fresh_otp_row = db.get(EmailOtpCode, otp_row_id)
        fresh_otp_row.consumed_at = datetime.now(timezone.utc)
        db.commit()

    return _issue_tokens(db, user, family_id=uuid.uuid4(), device_info=payload.device_info)
