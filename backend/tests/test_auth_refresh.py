import uuid
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.models import RefreshToken, User
from app.security import generate_refresh_token, hash_refresh_token
from tests.conftest import make_google_identity


def _login(client: TestClient, set_google_identity, subject_id: str) -> dict:
    set_google_identity(make_google_identity(subject_id=subject_id))
    response = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert response.status_code == 200
    return response.json()


def test_refresh_rotates_token(client: TestClient, set_google_identity, db_session):
    tokens = _login(client, set_google_identity, "sub-refresh-rotate")

    response = client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})

    assert response.status_code == 200
    new_tokens = response.json()
    assert new_tokens["refresh_token"] != tokens["refresh_token"]
    assert new_tokens["access_token"] != tokens["access_token"]

    old_row = db_session.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(tokens["refresh_token"]))
    )
    assert old_row.revoked_at is not None
    assert old_row.replaced_by_id is not None

    new_row = db_session.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(new_tokens["refresh_token"]))
    )
    assert new_row.revoked_at is None
    assert new_row.family_id == old_row.family_id


def test_refresh_with_unknown_token_is_rejected(client: TestClient):
    response = client.post("/v1/auth/refresh", json={"refresh_token": "not-a-real-token"})

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_refresh_token"


def test_refresh_with_expired_token_is_rejected(client: TestClient, db_session):
    user = User(id=uuid.uuid4())
    db_session.add(user)
    db_session.flush()

    raw_token, token_hash = generate_refresh_token()
    db_session.add(
        RefreshToken(
            id=uuid.uuid4(),
            user_id=user.id,
            family_id=uuid.uuid4(),
            token_hash=token_hash,
            expires_at=datetime.now(timezone.utc) - timedelta(days=1),
        )
    )
    db_session.commit()

    response = client.post("/v1/auth/refresh", json={"refresh_token": raw_token})

    assert response.status_code == 401
    assert response.json()["detail"] == "refresh_token_expired"


def test_reusing_a_revoked_refresh_token_revokes_the_whole_family(client: TestClient, set_google_identity, db_session):
    tokens = _login(client, set_google_identity, "sub-reuse-detection")

    rotated = client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert rotated.status_code == 200
    current_tokens = rotated.json()

    # Replay the original (now-revoked) refresh token, as an attacker with a
    # stolen copy of it would.
    reuse_response = client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})

    assert reuse_response.status_code == 401
    assert reuse_response.json()["detail"] == "refresh_token_reused"

    # The still-valid, rotated token must now be revoked too (whole family burned).
    still_valid_attempt = client.post("/v1/auth/refresh", json={"refresh_token": current_tokens["refresh_token"]})
    assert still_valid_attempt.status_code == 401
    assert still_valid_attempt.json()["detail"] == "refresh_token_reused"

    old_row = db_session.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(tokens["refresh_token"]))
    )
    family_rows = db_session.scalars(
        select(RefreshToken).where(RefreshToken.family_id == old_row.family_id)
    ).all()
    assert all(row.revoked_at is not None for row in family_rows)
