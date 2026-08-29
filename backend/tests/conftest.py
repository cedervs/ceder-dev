import os
import uuid
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session as SASession

from app.auth.google import GoogleIdentity
from app.db import Base, get_db
from app.deps import get_google_verifier
from app.main import app

TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+psycopg://worlddiscovery_test:worlddiscovery_test@localhost:5433/worlddiscovery_test",
)


@pytest.fixture(scope="session")
def engine():
    eng = create_engine(TEST_DATABASE_URL)
    Base.metadata.create_all(eng)
    yield eng
    Base.metadata.drop_all(eng)
    eng.dispose()


@pytest.fixture()
def db_session(engine) -> Iterator[SASession]:
    """Each test runs inside a SAVEPOINT that is rolled back afterwards, even
    though the code under test calls session.commit() freely."""
    connection = engine.connect()
    outer_transaction = connection.begin()
    session = SASession(bind=connection, join_transaction_mode="create_savepoint")
    try:
        yield session
    finally:
        session.close()
        outer_transaction.rollback()
        connection.close()


@pytest.fixture()
def client(db_session: SASession) -> Iterator[TestClient]:
    def _get_db_override() -> Iterator[SASession]:
        yield db_session

    app.dependency_overrides[get_db] = _get_db_override
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture()
def set_google_identity(client: TestClient) -> Callable[[GoogleIdentity], None]:
    """Lets a test control what the next Google Sign-In call(s) resolve to,
    without any network call to Google."""
    state: dict[str, GoogleIdentity | None] = {"identity": None}

    def _fake_verifier(_raw_id_token: str) -> GoogleIdentity:
        assert state["identity"] is not None, "call set_google_identity(...) before hitting /v1/auth/google"
        return state["identity"]

    app.dependency_overrides[get_google_verifier] = lambda: _fake_verifier

    def _set(identity: GoogleIdentity) -> None:
        state["identity"] = identity

    yield _set
    app.dependency_overrides.pop(get_google_verifier, None)


def make_google_identity(subject_id: str | None = None, email: str = "user@example.com") -> GoogleIdentity:
    return GoogleIdentity(
        subject_id=subject_id or f"google-subject-{uuid.uuid4()}",
        email=email,
        email_verified=True,
    )
