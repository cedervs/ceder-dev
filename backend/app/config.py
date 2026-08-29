from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    google_web_client_id: str
    jwt_secret: str
    access_token_ttl_minutes: int = 15
    refresh_token_ttl_days: int = 30


settings = Settings()
