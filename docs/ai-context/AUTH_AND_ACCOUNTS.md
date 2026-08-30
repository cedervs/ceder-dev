# Authentication and Accounts

## Current architecture — IMPLEMENTED
- Android Google Sign-In uses Credential Manager/Google ID credentials through `GoogleAuthProvider`.
- The Google ID token is exchanged with the FastAPI backend; feature code must not duplicate this boundary.
- Email authentication uses a six-digit OTP requested and verified through the same backend session architecture.
- `AuthRepository` centralizes sign-in, initialization, refresh and logout.
- The access token is held in memory only by `AuthRepository`.
- The refresh token is persisted on Android through Tink-backed `TinkAuthTokenStorage`.
- The backend implements Google verification, OTP, JWT access tokens, refresh sessions and logout with PostgreSQL/Alembic persistence.

## Refresh/session policy — IMPLEMENTED
Backend code and tests establish the current policy:
- a valid refresh rotates to a new refresh token and revokes/replaces the old token;
- reuse of a revoked/replaced token is detected and revokes the whole token family;
- expired and unknown refresh tokens are rejected;
- logout revokes the current refresh-token session and is idempotent for an unknown token;
- logging out one session does not revoke other sessions for the same user;
- Android initialization resumes a stored valid session and stores the rotated refresh token;
- failed refresh clears local refresh-token storage and signs out.

## Google/OTP identity linking — IMPLEMENTED
The current backend behavior is normative for the implemented system and is covered by tests:
- an existing `email_otp` identity for the normalized e-mail reuses its user;
- otherwise, OTP links to an existing Google user only when the exact same e-mail is currently verified by Google;
- otherwise, OTP creates a new user;
- two already-distinct users are never merged automatically;
- every Google login refreshes the stored Google e-mail and its verification state;
- an old or no-longer-verified Google e-mail stops being valid linking proof;
- account merge/recovery across two pre-existing distinct accounts is not implemented.

## OTP policy — IMPLEMENTED
- OTP codes are single-use and cannot be replayed.
- A new request invalidates the previous code.
- Codes expire; wrong attempts are counted and lock after the configured maximum.
- Resend cooldown is enforced.
- Hourly request limits exist per e-mail and per IP.
- Tests replace real Google verification and e-mail delivery with controlled fakes; do not weaken these security cases.

## Security invariants — DECIDED
- Never commit or log passwords, raw OTPs, access/refresh tokens, OAuth client secrets, signing secrets, private keys or other sensitive credentials.
- A public OAuth client ID is not a secret, but must still not be confused with an OAuth client secret.
- Authentication does not authorize client-side Certified discovery.
- Public profile data must never be accepted as account-recovery proof.

## Future work
- **DECIDED / NOT IMPLEMENTED:** export and account deletion are required before real production; optional recovery contacts and device/session management remain future surfaces.
- **NEEDS USER CONFIRMATION:** whether Apple or another provider is required; rules for an explicit recovery/merge flow between two already-distinct accounts; final deletion/export/privacy UX.
- Consult `docs/architecture.md` §7 and `docs/product-spec.md` §5 for the normative long-term account/profile separation.
