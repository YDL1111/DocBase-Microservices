"""
Application settings loaded from environment variables.
"""
from typing import List
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", case_sensitive=True, extra="ignore")

    # Application
    APP_NAME: str = "rag-service"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = False
    HOST: str = "0.0.0.0"
    PORT: int = 8090
    LOG_LEVEL: str = "INFO"

    # Database
    DATABASE_URL: str = "mysql+pymysql://docbase_rag:change-me@mysql:3306/docbase_rag"
    DATABASE_POOL_SIZE: int = 5
    DATABASE_MAX_OVERFLOW: int = 5

    # RabbitMQ — split into individual fields to avoid URL-encoding issues with vhost
    RABBITMQ_HOST: str = "rabbitmq"
    RABBITMQ_PORT: int = 5672
    RABBITMQ_USER: str = "docbase"
    RABBITMQ_PASSWORD: str = "change-me"
    RABBITMQ_VHOST: str = "/docbase"
    RABBITMQ_CONSUMER_PREFETCH: int = 1

    # MinIO
    MINIO_ENDPOINT: str = "http://minio:9000"
    MINIO_ACCESS_KEY: str = "docbase-app"
    MINIO_SECRET_KEY: str = "change-me"
    MINIO_BUCKET: str = "docbase-documents"
    MINIO_SECURE: bool = False
    MINIO_MAX_FILE_SIZE: int = 104857600  # 100MB

    # Chroma
    CHROMA_PERSIST_DIR: str = "/data/chroma"
    CHROMA_COLLECTION_PREFIX: str = "docbase"

    # Embedding
    EMBEDDING_PROVIDER: str = "huggingface"
    HF_EMBEDDING_MODEL: str = "BAAI/bge-m3"
    HF_EMBEDDING_DEVICE: str = "cpu"
    HF_NORMALIZE_EMBEDDINGS: bool = True
    HF_LOCAL_FILES_ONLY: bool = True
    HF_HUB_OFFLINE: bool = True
    HF_WARMUP_ON_STARTUP: bool = True
    TRANSFORMERS_OFFLINE: bool = True
    HF_HOME: str = "/cache/huggingface"

    # Chat model (any OpenAI-compatible provider)
    CHAT_API_KEY: str = "replace-with-chat-api-key"
    CHAT_BASE_URL: str = "https://api.openai.com/v1"
    CHAT_MODEL: str = "gpt-4o-mini"

    # RAG
    CHUNK_SIZE: int = 800
    CHUNK_OVERLAP: int = 100
    TOP_K: int = 8
    RETRIEVAL_CANDIDATE_K: int = 24
    RERANK_TOP_K: int = 8
    MMR_LAMBDA: float = 0.7
    NEAR_DUPLICATE_THRESHOLD: float = 0.92
    MIN_RELEVANCE_SCORE: float = 0.35
    MAX_CONTEXT_LENGTH: int = 6000
    MAX_CONTEXT_TOKENS: int = 4000
    QUERY_REWRITE_ENABLED: bool = True
    QUERY_REWRITE_TIMEOUT_SECONDS: float = 8.0
    HISTORY_MAX_MESSAGES: int = 12
    HISTORY_MAX_CHARS: int = 12000

    # Internal API Security
    INTERNAL_API_KEY: str = "replace-me-internal-key"

    # Nacos
    NACOS_SERVER: str = "nacos:8848"
    NACOS_NAMESPACE: str = "docbase-dev"
    NACOS_GROUP: str = "DOCBASE_GROUP"
    NACOS_USERNAME: str = "nacos"
    NACOS_PASSWORD: str = "change-me"
    NACOS_SERVICE_NAME: str = "rag-service"
    NACOS_HEARTBEAT_INTERVAL: int = 10

    # Allowed file extensions for parsing
    ALLOWED_EXTENSIONS: str = "pdf,docx,xlsx,pptx,txt,md,csv,json,xml,html"

    @property
    def allowed_extensions_list(self) -> List[str]:
        return [ext.strip().lower() for ext in self.ALLOWED_EXTENSIONS.split(",")]


@lru_cache()
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
