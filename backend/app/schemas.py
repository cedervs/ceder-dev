from pydantic import BaseModel, EmailStr


class GoogleAuthRequest(BaseModel):
    id_token: str
    device_info: str | None = None


class RequestEmailCodeRequest(BaseModel):
    email: EmailStr
    locale: str | None = None


class VerifyEmailCodeRequest(BaseModel):
    email: EmailStr
    code: str
    device_info: str | None = None


class RefreshRequest(BaseModel):
    refresh_token: str


class LogoutRequest(BaseModel):
    refresh_token: str


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
