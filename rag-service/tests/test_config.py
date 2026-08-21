"""
Tests for configuration loading.
"""
import os
from unittest.mock import patch

import pytest

from app.core.config import Settings


def test_default_settings():
    """Test default configuration values."""
    settings = Settings()
    assert settings.APP_NAME == "rag-service"
    assert settings.PORT == 8090
    assert settings.EMBEDDING_PROVIDER == "huggingface"
    assert settings.HF_EMBEDDING_MODEL == "BAAI/bge-m3"
    assert settings.HF_WARMUP_ON_STARTUP is True
    assert settings.CHAT_BASE_URL == "https://api.openai.com/v1"
    assert settings.CHAT_MODEL == "gpt-4o-mini"
    assert settings.RETRIEVAL_CANDIDATE_K == 24
    assert settings.RERANK_TOP_K == 8
    assert settings.MMR_LAMBDA == 0.7


def test_env_override():
    """Test that environment variables override defaults."""
    with patch.dict(os.environ, {
        "PORT": "9090",
        "APP_NAME": "test-rag",
        "CHAT_API_KEY": "test-key",
        "CHAT_BASE_URL": "https://chat.example.test/v1",
        "CHAT_MODEL": "example-chat",
    }):
        settings = Settings()
        assert settings.PORT == 9090
        assert settings.APP_NAME == "test-rag"
        assert settings.CHAT_API_KEY == "test-key"
        assert settings.CHAT_BASE_URL == "https://chat.example.test/v1"
        assert settings.CHAT_MODEL == "example-chat"


def test_allowed_extensions():
    """Test allowed extensions parsing."""
    settings = Settings()
    exts = settings.allowed_extensions_list
    assert "pdf" in exts
    assert "docx" in exts
    assert "txt" in exts
