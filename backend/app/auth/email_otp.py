"""One-time-code generation, hashing and email template rendering for email sign-in.

Security notes:
- The code is 6 decimal digits (1,000,000 possibilities) — far too small a space for a
  plain, unsalted/unkeyed hash to resist offline brute force if the database ever leaked
  (recomputing sha256 for all 1M values takes milliseconds). It is therefore always hashed
  with HMAC-SHA256 keyed by a server-only secret (OTP_HASH_SECRET, distinct from the JWT
  signing secret so a leak of one does not compromise the other), which an attacker with
  database access alone cannot invert.
- The code itself must never appear in application logs — see app/auth/email_sender.py for
  how it reaches the user instead (a real, delivered email, not a log line).
"""

import hashlib
import hmac
import secrets

from app.config import settings

OTP_CODE_LENGTH = 6


def generate_otp_code() -> str:
    return f"{secrets.randbelow(10 ** OTP_CODE_LENGTH):0{OTP_CODE_LENGTH}d}"


def hash_otp_code(email: str, code: str) -> str:
    message = f"{email}:{code}".encode("utf-8")
    return hmac.new(settings.otp_hash_secret.encode("utf-8"), message, hashlib.sha256).hexdigest()


def verify_otp_code(email: str, code: str, expected_hash: str) -> bool:
    return hmac.compare_digest(hash_otp_code(email, code), expected_hash)


def render_otp_email(code: str, locale: str | None) -> tuple[str, str]:
    """Returns (subject, plain-text body). Falls back to English for any locale that
    isn't explicitly supported. This is the one place backend-rendered user-facing text
    is acceptable (architecture.md §9): it leaves the app via an external channel (email),
    so it cannot be translated client-side like normal API responses are."""
    templates = {
        "fr": (
            "Votre code de connexion World Discovery",
            f"Votre code de connexion est : {code}\n\nCe code expire dans {settings.otp_ttl_minutes} minutes "
            "et ne peut être utilisé qu'une seule fois. Si vous n'êtes pas à l'origine de cette demande, "
            "ignorez cet email.",
        ),
        "en": (
            "Your World Discovery sign-in code",
            f"Your sign-in code is: {code}\n\nThis code expires in {settings.otp_ttl_minutes} minutes and can "
            "only be used once. If you did not request this, you can safely ignore this email.",
        ),
    }
    return templates.get((locale or "en").lower(), templates["en"])
