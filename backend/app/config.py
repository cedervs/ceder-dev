from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    google_web_client_id: str
    jwt_secret: str
    access_token_ttl_minutes: int = 15
    refresh_token_ttl_days: int = 30

    otp_hash_secret: str
    otp_ttl_minutes: int = 10
    otp_max_attempts: int = 5
    otp_resend_cooldown_seconds: int = 60
    otp_max_requests_per_email_per_hour: int = 5
    otp_max_requests_per_ip_per_hour: int = 20

    smtp_host: str = "localhost"
    smtp_port: int = 1025
    smtp_from_address: str = "no-reply@worlddiscovery.local"


settings = Settings()
