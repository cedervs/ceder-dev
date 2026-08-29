from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.auth.google import GoogleIdentity
from app.models import AuthIdentity, EmailOtpCode
from app.security import decode_access_token
from tests.conftest import FakeEmailSender, make_google_identity


def _request_code(client: TestClient, email: str, locale: str | None = None) -> None:
    response = client.post("/v1/auth/email/request-code", json={"email": email, "locale": locale})
    assert response.status_code == 202, response.text


def _verify_code(client: TestClient, email: str, code: str) -> dict:
    response = client.post("/v1/auth/email/verify-code", json={"email": email, "code": code})
    assert response.status_code == 200, response.text
    return response.json()


def _request_and_verify(client: TestClient, fake_email_sender: FakeEmailSender, email: str) -> dict:
    _request_code(client, email)
    code = fake_email_sender.latest_code_for(email)
    return _verify_code(client, email, code)


def _user_id_from(tokens: dict) -> str:
    return decode_access_token(tokens["access_token"])["sub"]


def _bypass_resend_cooldown(db_session, email: str) -> None:
    """Backdates the most recent code's created_at so the next request-code call in a
    test isn't itself rejected by the (correctly working) 60s resend cooldown."""
    otp_row = db_session.scalar(
        select(EmailOtpCode).where(EmailOtpCode.email == email).order_by(EmailOtpCode.created_at.desc()).limit(1)
    )
    otp_row.created_at = datetime.now(timezone.utc) - timedelta(seconds=61)
    db_session.commit()


# --- Core happy path -------------------------------------------------------------


def test_request_code_sends_exactly_one_email(client: TestClient, fake_email_sender: FakeEmailSender):
    _request_code(client, "new-user@example.com")

    assert len(fake_email_sender.sent) == 1
    to, subject, body = fake_email_sender.sent[0]
    assert to == "new-user@example.com"
    assert subject and body


def test_verify_code_creates_a_new_user_and_issues_tokens(
    client: TestClient, fake_email_sender: FakeEmailSender, db_session
):
    tokens = _request_and_verify(client, fake_email_sender, "new-user@example.com")

    assert tokens["access_token"]
    assert tokens["refresh_token"]

    identity = db_session.scalar(
        select(AuthIdentity).where(
            AuthIdentity.provider == "email_otp", AuthIdentity.provider_subject_id == "new-user@example.com"
        )
    )
    assert identity is not None
    assert identity.email == "new-user@example.com"
    assert identity.verified_at is not None


def test_verify_code_is_idempotent_reuses_same_user_across_logins(
    client: TestClient, fake_email_sender: FakeEmailSender, db_session
):
    first = _request_and_verify(client, fake_email_sender, "returning-user@example.com")
    _bypass_resend_cooldown(db_session, "returning-user@example.com")
    second = _request_and_verify(client, fake_email_sender, "returning-user@example.com")

    assert _user_id_from(first) == _user_id_from(second)

    identities = db_session.scalars(
        select(AuthIdentity).where(
            AuthIdentity.provider == "email_otp", AuthIdentity.provider_subject_id == "returning-user@example.com"
        )
    ).all()
    assert len(identities) == 1


def test_new_code_request_invalidates_the_previous_code(
    client: TestClient, fake_email_sender: FakeEmailSender, db_session
):
    _request_code(client, "user@example.com")
    old_code = fake_email_sender.latest_code_for("user@example.com")

    _bypass_resend_cooldown(db_session, "user@example.com")
    _request_code(client, "user@example.com")

    response = client.post("/v1/auth/email/verify-code", json={"email": "user@example.com", "code": old_code})
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_code"


def test_verified_code_cannot_be_replayed(client: TestClient, fake_email_sender: FakeEmailSender):
    _request_code(client, "user@example.com")
    code = fake_email_sender.latest_code_for("user@example.com")
    _verify_code(client, "user@example.com", code)

    replay = client.post("/v1/auth/email/verify-code", json={"email": "user@example.com", "code": code})
    assert replay.status_code == 401
    assert replay.json()["detail"] == "invalid_code"


# --- Error / limit handling --------------------------------------------------------


def test_verify_code_with_no_code_requested_is_rejected(client: TestClient):
    response = client.post("/v1/auth/email/verify-code", json={"email": "nobody@example.com", "code": "123456"})
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_code"


def test_wrong_code_increments_attempts_and_locks_after_max(client: TestClient, fake_email_sender: FakeEmailSender):
    _request_code(client, "user@example.com")

    for _ in range(5):
        response = client.post("/v1/auth/email/verify-code", json={"email": "user@example.com", "code": "000000"})
        assert response.status_code == 401
        assert response.json()["detail"] == "invalid_code"

    correct_code = fake_email_sender.latest_code_for("user@example.com")
    locked_response = client.post(
        "/v1/auth/email/verify-code", json={"email": "user@example.com", "code": correct_code}
    )
    assert locked_response.status_code == 401
    assert locked_response.json()["detail"] == "too_many_attempts"


def test_expired_code_is_rejected(client: TestClient, fake_email_sender: FakeEmailSender, db_session):
    _request_code(client, "user@example.com")
    code = fake_email_sender.latest_code_for("user@example.com")

    otp_row = db_session.scalar(select(EmailOtpCode).where(EmailOtpCode.email == "user@example.com"))
    otp_row.expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
    db_session.commit()

    response = client.post("/v1/auth/email/verify-code", json={"email": "user@example.com", "code": code})
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_code"


def test_resend_before_cooldown_is_rejected(client: TestClient, fake_email_sender: FakeEmailSender):
    _request_code(client, "user@example.com")

    response = client.post("/v1/auth/email/request-code", json={"email": "user@example.com"})

    assert response.status_code == 429
    assert response.json()["detail"] == "resend_cooldown"


def test_resend_after_cooldown_is_allowed(client: TestClient, fake_email_sender: FakeEmailSender, db_session):
    _request_code(client, "user@example.com")

    otp_row = db_session.scalar(select(EmailOtpCode).where(EmailOtpCode.email == "user@example.com"))
    otp_row.created_at = datetime.now(timezone.utc) - timedelta(seconds=61)
    db_session.commit()

    _request_code(client, "user@example.com")
    assert len(fake_email_sender.sent) == 2


def test_hourly_request_limit_per_email_is_enforced(client: TestClient, fake_email_sender: FakeEmailSender, db_session):
    for _ in range(5):
        _request_code(client, "heavy-user@example.com")
        otp_row = db_session.scalar(
            select(EmailOtpCode)
            .where(EmailOtpCode.email == "heavy-user@example.com")
            .order_by(EmailOtpCode.created_at.desc())
            .limit(1)
        )
        # Bypass the resend cooldown between iterations without touching the hourly window.
        otp_row.created_at = datetime.now(timezone.utc) - timedelta(seconds=61)
        db_session.commit()

    response = client.post("/v1/auth/email/request-code", json={"email": "heavy-user@example.com"})
    assert response.status_code == 429
    assert response.json()["detail"] == "too_many_requests"


def test_hourly_request_limit_per_ip_is_enforced(client: TestClient, fake_email_sender: FakeEmailSender, db_session):
    # TestClient reports a fixed fake client address, so every request in this test
    # shares the same IP — enough to exercise the per-IP counter across many emails.
    for i in range(20):
        email = f"ip-test-{i}@example.com"
        _request_code(client, email)

    response = client.post("/v1/auth/email/request-code", json={"email": "ip-test-overflow@example.com"})
    assert response.status_code == 429
    assert response.json()["detail"] == "too_many_requests"


# --- Account-linking rule (Auth 2B, validated 2026-08-29) --------------------------
#
# See _find_or_create_user_for_verified_email in app/routers/auth.py for the exact rule.
# These tests exist so that rule cannot silently change later.


def test_email_links_to_existing_user_when_google_email_is_verified(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity, db_session
):
    set_google_identity(make_google_identity(subject_id="g-sub-1", email="shared@example.com"))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    email_login = _request_and_verify(client, fake_email_sender, "shared@example.com")

    assert _user_id_from(google_login.json()) == _user_id_from(email_login)

    identities = db_session.scalars(
        select(AuthIdentity).where(AuthIdentity.email == "shared@example.com")
    ).all()
    assert {i.provider for i in identities} == {"google", "email_otp"}
    assert len({i.user_id for i in identities}) == 1


def test_email_does_not_link_when_google_email_is_not_verified(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity, db_session
):
    set_google_identity(GoogleIdentity(subject_id="g-sub-2", email="unverified@example.com", email_verified=False))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    email_login = _request_and_verify(client, fake_email_sender, "unverified@example.com")

    assert _user_id_from(google_login.json()) != _user_id_from(email_login)

    identities = db_session.scalars(
        select(AuthIdentity).where(AuthIdentity.email == "unverified@example.com")
    ).all()
    assert len({i.user_id for i in identities}) == 2


def test_email_does_not_link_when_no_matching_google_identity_exists(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity
):
    set_google_identity(make_google_identity(subject_id="g-sub-3", email="google-only@example.com"))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    email_login = _request_and_verify(client, fake_email_sender, "completely-different@example.com")

    assert _user_id_from(google_login.json()) != _user_id_from(email_login)


def test_linking_never_merges_two_already_distinct_existing_users(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity, db_session
):
    """Two people already have their own separate accounts (one via Google, one via
    email). Nothing about a third, unrelated email verification may ever touch either
    of them."""
    set_google_identity(make_google_identity(subject_id="g-sub-4", email="person-a@example.com"))
    person_a = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert person_a.status_code == 200

    person_b = _request_and_verify(client, fake_email_sender, "person-b@example.com")

    person_c = _request_and_verify(client, fake_email_sender, "person-c@example.com")

    user_ids = {_user_id_from(person_a.json()), _user_id_from(person_b), _user_id_from(person_c)}
    assert len(user_ids) == 3


# --- Google email refresh must not leave stale linking proof (fix validated 2026-08-29) --
#
# Risk being closed: auth_identities.email/verified_at used to be frozen at first Google
# signup. If a Google account's email later changed and the old address were reassigned
# to someone else, that someone else could request an OTP on the old address and get
# silently linked to the original owner's World Discovery account. login_with_google now
# refreshes (email, verified_at) from the *current* token on every login — see
# _find_or_create_user_for_verified_email's docstring in app/routers/auth.py.


def test_google_email_change_is_reflected_before_any_otp_linking_decision(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity, db_session
):
    set_google_identity(make_google_identity(subject_id="g-sub-email-change", email="old@example.com"))
    first_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert first_login.status_code == 200

    set_google_identity(make_google_identity(subject_id="g-sub-email-change", email="new@example.com"))
    second_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert second_login.status_code == 200

    identity_row = db_session.scalar(
        select(AuthIdentity).where(AuthIdentity.provider_subject_id == "g-sub-email-change")
    )
    assert identity_row.email == "new@example.com"
    assert identity_row.verified_at is not None


def test_old_google_email_no_longer_allows_new_otp_linking_after_change(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity
):
    set_google_identity(make_google_identity(subject_id="g-sub-old-email", email="old-owner@example.com"))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    # The Google account's email changes — someone else could now legitimately own
    # "old-owner@example.com" and prove it via OTP.
    set_google_identity(make_google_identity(subject_id="g-sub-old-email", email="current@example.com"))
    client.post("/v1/auth/google", json={"id_token": "fake"})

    new_claimant = _request_and_verify(client, fake_email_sender, "old-owner@example.com")

    assert _user_id_from(new_claimant) != _user_id_from(google_login.json()), (
        "the old email must never link to the original Google account once it is no "
        "longer that account's verified email"
    )


def test_new_google_email_allows_otp_linking(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity
):
    set_google_identity(make_google_identity(subject_id="g-sub-new-email", email="old@example.com"))
    client.post("/v1/auth/google", json={"id_token": "fake"})

    set_google_identity(make_google_identity(subject_id="g-sub-new-email", email="new@example.com"))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    email_login = _request_and_verify(client, fake_email_sender, "new@example.com")

    assert _user_id_from(email_login) == _user_id_from(google_login.json())


def test_google_login_without_verified_email_leaves_no_exploitable_linking_proof(
    client: TestClient, fake_email_sender: FakeEmailSender, set_google_identity
):
    set_google_identity(make_google_identity(subject_id="g-sub-unverifies", email="verified-once@example.com"))
    google_login = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert google_login.status_code == 200

    # Same account, but this login no longer carries a verified email (e.g. Google could
    # not vouch for it this time) — the previously-verified email must stop being usable
    # as proof, even though it may still be the value stored in the email column.
    set_google_identity(
        GoogleIdentity(subject_id="g-sub-unverifies", email="verified-once@example.com", email_verified=False)
    )
    client.post("/v1/auth/google", json={"id_token": "fake"})

    new_claimant = _request_and_verify(client, fake_email_sender, "verified-once@example.com")

    assert _user_id_from(new_claimant) != _user_id_from(google_login.json())
