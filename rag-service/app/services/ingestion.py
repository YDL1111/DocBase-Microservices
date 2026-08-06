"""
RAG ingestion service - handles document processing workflow.
"""
import hashlib
import tempfile
import os
from typing import Optional

from app.core.config import settings
from app.core.logging import get_logger
from app.services.parser import DocumentParser
from app.services.chunker import TextChunker
from app.services.vector_store import vector_store
from app.services.object_storage import object_storage

logger = get_logger(__name__)


class IngestionService:
    """Orchestrates document ingestion: download → parse → chunk → embed → store."""

    def __init__(self):
        self.parser = DocumentParser()
        self.chunker = TextChunker()

    async def ingest_document(
        self,
        knowledge_base_id: int,
        document_id: int,
        version_id: int,
        object_key: str,
        file_name: str,
        content_type: Optional[str] = None,
    ) -> dict:
        """
        Process a document for RAG ingestion.

        Returns:
            dict with chunk_count and content_hash

        Raises:
            ValueError: If document type is unsupported
            Exception: If processing fails
        """
        logger.info(f"Starting ingestion: KB={knowledge_base_id}, Doc={document_id}, v={version_id}")

        # Download file from MinIO
        file_data = object_storage.download_file(object_key)
        content_hash = hashlib.sha256(file_data).hexdigest()

        # Write to temp file for parsing
        ext = os.path.splitext(file_name)[1] if file_name else ""
        with tempfile.NamedTemporaryFile(suffix=ext, delete=False) as tmp:
            tmp.write(file_data)
            tmp_path = tmp.name

        try:
            # Parse document
            documents, meta = self.parser.parse(tmp_path, file_name)

            # Add metadata to documents
            for doc in documents:
                doc.metadata.update({
                    "knowledge_base_id": knowledge_base_id,
                    "document_id": document_id,
                    "version_id": version_id,
                    "file_name": file_name,
                    "content_hash": content_hash,
                })

            # Chunk documents
            chunks = self.chunker.chunk_documents(documents)

            # Store in vector database
            chunk_count = vector_store.upsert_chunks(
                knowledge_base_id, document_id, version_id, chunks
            )

            logger.info(f"Ingestion complete: {chunk_count} chunks for doc {document_id}")

            return {
                "chunk_count": chunk_count,
                "content_hash": content_hash,
                "file_type": meta.get("file_type"),
            }

        finally:
            # Clean up temp file
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    async def delete_document(self, knowledge_base_id: int, document_id: int) -> int:
        """Delete all chunks for a document."""
        return vector_store.delete_document(knowledge_base_id, document_id)

    async def delete_document_version(self, knowledge_base_id: int, document_id: int,
                                      version_id: int) -> int:
        """Delete chunks for a specific document version."""
        return vector_store.delete_document_version(knowledge_base_id, document_id, version_id)


# Singleton instance
ingestion_service = IngestionService()
