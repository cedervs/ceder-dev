from fastapi.testclient import TestClient

from tests.conftest import make_google_identity


def _login(client: TestClient, set_google_identity, subject_id: str) -> dict:
    set_google_identity(make_google_identity(subject_id=subject_id))
    response = client.post("/v1/auth/google", json={"id_token": "fake"})
    assert response.status_code == 200
    return response.json()


def test_logout_revokes_current_session(client: TestClient, set_google_identity):
    tokens = _login(client, set_google_identity, "sub-logout")

    logout_response = client.post("/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]})
    assert logout_response.status_code == 204

    reuse_attempt = client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert reuse_attempt.status_code == 401


def test_logout_with_unknown_token_is_idempotent(client: TestClient):
    response = client.post("/v1/auth/logout", json={"refresh_token": "garbage-token"})

    assert response.status_code == 204


def test_logout_only_revokes_the_current_session_not_other_devices(client: TestClient, set_google_identity):
    # Same Google identity signing in from two different devices/sessions.
    device_a = _login(client, set_google_identity, "sub-multi-device")
    device_b = _login(client, set_google_identity, "sub-multi-device")

    logout_response = client.post("/v1/auth/logout", json={"refresh_token": device_a["refresh_token"]})
    assert logout_response.status_code == 204

    still_works = client.post("/v1/auth/refresh", json={"refresh_token": device_b["refresh_token"]})
    assert still_works.status_code == 200
