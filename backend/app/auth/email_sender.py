"""Outbound email, isolated behind an interface (architecture.md §10: no strong dependency
on a specific external provider without an internal seam). The dev implementation talks to
a local SMTP catcher (Mailpit); production will need a real provider (SES/Postmark/SendGrid/
Resend/...) — that choice is deferred, not decided here, and only requires a new class
implementing EmailSender, no router/business-logic changes."""

import smtplib
from abc import ABC, abstractmethod
from email.message import EmailMessage

from app.config import settings


class EmailSender(ABC):
    @abstractmethod
    def send(self, to: str, subject: str, body: str) -> None: ...


class SmtpEmailSender(EmailSender):
    """Sends real SMTP mail. In dev this points at Mailpit (docker-compose), so the OTP
    code is readable in Mailpit's web UI — never in application logs."""

    def send(self, to: str, subject: str, body: str) -> None:
        message = EmailMessage()
        message["From"] = settings.smtp_from_address
        message["To"] = to
        message["Subject"] = subject
        message.set_content(body)

        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=10) as smtp:
            smtp.send_message(message)
