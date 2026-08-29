from collections.abc import Callable

from app.auth.email_sender import EmailSender, SmtpEmailSender
from app.auth.google import GoogleIdentity, verify_google_id_token


def get_google_verifier() -> Callable[[str], GoogleIdentity]:
    """FastAPI dependency indirection so tests can override with a fake verifier
    instead of calling out to Google's servers."""
    return verify_google_id_token


_smtp_email_sender = SmtpEmailSender()


def get_email_sender() -> EmailSender:
    """FastAPI dependency indirection so tests can override with a fake sender instead
    of talking to a real (or Mailpit) SMTP server."""
    return _smtp_email_sender
