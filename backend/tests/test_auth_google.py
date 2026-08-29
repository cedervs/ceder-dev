from fastapi.testclient import TestClient
from sqlalchemy import select

from app.auth.google import GoogleIdentity, InvalidGoogleTokenError
from app.deps import get_google_verifier
from app.main import app
from app.models import AuthIdentity, User
from app.security import decode_access_token
from tests.conftest import make_google_identity


def test_new_user_created_on_first_google_login(client: TestClient, set_google_identity, db_session):
    set_google_identity(make_google_identity(subject_id="sub-new-user"))

    response = client.post("/v1/auth/google", json={"id_token": "fake"})

    assert response.status_code == 200
    body = response.json()
    assert body["access_token"]
    assert body["refresh_token"]
    assert body["token_type"] == "bearer"

    assert db_session.scalar(select(User)).id is not None
    identity_row = db_session.scalar(
        select(AuthIdentity).where(AuthIdentity.provider_subject_id == "sub-new-user")
    )
    assert identity_row is not None
    assert identity_row.email == "user@example.com"
    assert identity_row.verified_at is not None


def test_google_login_is_idempotent_for_same_identity(client: TestClient, set_google_identity, db_session):
    set_google_identity(make_google_identity(subject_id="sub-idempotent"))

    first = client.post("/v1/auth/google", json={"id_token": "fake"})
    second = client.post("/v1/auth/google", json={"id_token": "fake"})

    assert first.status_code == 200
    assert second.status_code == 200

    first_user_id = decode_access_token(first.json()["access_token"])["sub"]
    second_user_id = decode_access_token(second.json()["access_token"])["sub"]
    assert first_user_id == second_user_id

    matching_identities = db_session.scalars(
        select(AuthIdentity).where(AuthIdentity.provider_subject_id == "sub-idempotent")
    ).all()
    assert len(matching_identities) == 1

    all_users = db_session.scalars(select(User)).all()
    assert len(all_users) == 1


def test_google_login_refreshes_email_and_verified_at_on_each_login(
    client: TestClient, set_google_identity, db_session
):
    set_google_identity(make_google_identity(subject_id="sub-email-change", email="old@example.com"))
    first = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert first.status_code == 200

    set_google_identity(make_google_identity(subject_id="sub-email-change", email="new@example.com"))
    second = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert second.status_code == 200

    # Same person, same Google account (provider_subject_id unchanged) => same user.
    assert decode_access_token(first.json()["access_token"])["sub"] == decode_access_token(
        second.json()["access_token"]
    )["sub"]

    identity_row = db_session.scalar(select(AuthIdentity).where(AuthIdentity.provider_subject_id == "sub-email-change"))
    assert identity_row.email == "new@example.com"
    assert identity_row.verified_at is not None

    matching_identities = db_session.scalars(
        select(AuthIdentity).where(AuthIdentity.provider_subject_id == "sub-email-change")
    ).all()
    assert len(matching_identities) == 1, "must update the existing row, never create a second identity"


def test_google_login_without_verified_email_clears_verified_at(client: TestClient, set_google_identity, db_session):
    set_google_identity(make_google_identity(subject_id="sub-loses-verification", email="verified@example.com"))
    first = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert first.status_code == 200

    set_google_identity(
        GoogleIdentity(subject_id="sub-loses-verification", email="verified@example.com", email_verified=False)
    )
    second = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert second.status_code == 200

    identity_row = db_session.scalar(
        select(AuthIdentity).where(AuthIdentity.provider_subject_id == "sub-loses-verification")
    )
    assert identity_row.verified_at is None


def test_google_login_rejects_invalid_token(client: TestClient):
    def _raise(_raw_id_token: str):
        raise InvalidGoogleTokenError("bad signature")

    app.dependency_overrides[get_google_verifier] = lambda: _raise
    try:
        response = client.post("/v1/auth/google", json={"id_token": "not-a-real-token"})
    finally:
        app.dependency_overrides.pop(get_google_verifier, None)

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_google_token"
