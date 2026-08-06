"""
Embedding service using BAAI/bge-m3.
"""
import functools
from typing import List

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)

# Module-level cache for the embedding model
_embedding_model = None


def get_embedding_model():
    """Lazy-load and cache the embedding model."""
    global _embedding_model
    if _embedding_model is None:
        logger.info(f"Loading embedding model: {settings.HF_EMBEDDING_MODEL}")
        from langchain_huggingface import HuggingFaceEmbeddings

        _embedding_model = HuggingFaceEmbeddings(
            model_name=settings.HF_EMBEDDING_MODEL,
            model_kwargs={
                "device": settings.HF_EMBEDDING_DEVICE,
                "local_files_only": settings.HF_LOCAL_FILES_ONLY,
            },
            encode_kwargs={
                "normalize_embeddings": settings.HF_NORMALIZE_EMBEDDINGS,
            },
        )
        logger.info("Embedding model loaded successfully")
    return _embedding_model


class EmbeddingService:
    """Service for generating text embeddings."""

    def __init__(self):
        self._model = None

    @property
    def model(self):
        """Lazy-load the embedding model."""
        if self._model is None:
            self._model = get_embedding_model()
        return self._model

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """Generate embeddings for a list of texts."""
        if not texts:
            return []
        return self.model.embed_documents(texts)

    def embed_query(self, text: str) -> List[float]:
        """Generate embedding for a single query."""
        return self.model.embed_query(text)

    @functools.lru_cache(maxsize=1024)
    def embed_query_cached(self, text: str) -> tuple:
        """Cached embedding for repeated queries. Returns tuple for hashability."""
        return tuple(self.embed_query(text))


# Singleton instance
embedding_service = EmbeddingService()
