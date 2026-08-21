"""Cross-instance serialization for a document's vector lifecycle."""
from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import text

from app.db.session import SessionLocal


class DocumentLockTimeoutError(RuntimeError):
    """Raised when the database document lock cannot be acquired."""

    def __init__(self, knowledge_base_id: int, document_id: int):
        super().__init__(
            f"DOCUMENT_LOCK_TIMEOUT: knowledge_base_id={knowledge_base_id}, "
            f"document_id={document_id}"
        )


class DocumentLifecycleLock:
    """Use a MySQL named lock to serialize Chroma writes for one document."""

    def __init__(self, timeout_seconds: int = 30):
        self.timeout_seconds = timeout_seconds

    @staticmethod
    def _lock_name(knowledge_base_id: int, document_id: int) -> str:
        # MySQL limits named locks to 64 characters. Numeric identifiers keep this bounded.
        return f"docbase-rag:{knowledge_base_id}:{document_id}"

    @contextmanager
    def acquire(self, knowledge_base_id: int, document_id: int) -> Iterator[None]:
        session = SessionLocal()
        lock_name = self._lock_name(knowledge_base_id, document_id)
        acquired = False
        try:
            result = session.execute(
                text("SELECT GET_LOCK(:lock_name, :timeout_seconds)"),
                {"lock_name": lock_name, "timeout_seconds": self.timeout_seconds},
            ).scalar()
            acquired = result == 1
            if not acquired:
                raise DocumentLockTimeoutError(knowledge_base_id, document_id)
            yield
        finally:
            try:
                if acquired:
                    session.execute(
                        text("SELECT RELEASE_LOCK(:lock_name)"),
                        {"lock_name": lock_name},
                    )
            finally:
                # Closing the connection is MySQL's fallback lock release path.
                session.close()


document_lifecycle_lock = DocumentLifecycleLock()
