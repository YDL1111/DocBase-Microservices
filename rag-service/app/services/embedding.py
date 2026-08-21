"""
Embedding service using BAAI/bge-m3.
"""
import functools
import threading
import time
from typing import List

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)

# Module-level cache for the embedding model
_embedding_model = None
_embedding_model_lock = threading.Lock()


def get_embedding_model():
    """Lazy-load and cache the embedding model."""
    global _embedding_model
    if _embedding_model is not None:
        return _embedding_model
    with _embedding_model_lock:
        if _embedding_model is not None:
            return _embedding_model
        started_at = time.perf_counter()
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
        logger.info(
            "Embedding model loaded model=%s load_ms=%.1f",
            settings.HF_EMBEDDING_MODEL,
            (time.perf_counter() - started_at) * 1000,
        )
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
        """Generate an embedding, reusing exact repeated queries."""
        return list(self._embed_query_cached(text))

    @functools.lru_cache(maxsize=1024)
    def _embed_query_cached(self, text: str) -> tuple[float, ...]:
        return tuple(self.model.embed_query(text))

    def warmup(self) -> None:
        """Load BGE-M3 and initialize its CPU inference kernels before serving traffic."""
        started_at = time.perf_counter()
        vector = self.embed_query("DocBase embedding warmup")
        if not vector:
            raise RuntimeError("Embedding warmup returned an empty vector")
        logger.info(
            "Embedding model warmup completed dimensions=%d warmup_ms=%.1f",
            len(vector),
            (time.perf_counter() - started_at) * 1000,
        )


# Singleton instance
embedding_service = EmbeddingService()
