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


def test_env_override():
    """Test that environment variables override defaults."""
    with patch.dict(os.environ, {"PORT": "9090", "APP_NAME": "test-rag"}):
        settings = Settings()
        assert settings.PORT == 9090
        assert settings.APP_NAME == "test-rag"


def test_allowed_extensions():
    """Test allowed extensions parsing."""
    settings = Settings()
    exts = settings.allowed_extensions_list
    assert "pdf" in exts
    assert "docx" in exts
    assert "txt" in exts
