"""
Vector store service using ChromaDB.
"""
import os
from typing import List, Optional, Dict, Any

from langchain_chroma import Chroma
from langchain_core.documents import Document

from app.core.config import settings
from app.core.logging import get_logger
from app.services.embedding import embedding_service

logger = get_logger(__name__)


class VectorStoreService:
    """Manages Chroma vector store operations."""

    def __init__(self):
        self._collections: Dict[str, Chroma] = {}

    def _get_collection_name(self, knowledge_base_id: int) -> str:
        """Generate collection name for a knowledge base."""
        return f"{settings.CHROMA_COLLECTION_PREFIX}_kb{knowledge_base_id}"

    def _get_collection(self, knowledge_base_id: int) -> Chroma:
        """Get or create a Chroma collection."""
        collection_name = self._get_collection_name(knowledge_base_id)

        if collection_name not in self._collections:
            logger.info(f"Initializing Chroma collection: {collection_name}")
            os.makedirs(settings.CHROMA_PERSIST_DIR, exist_ok=True)

            self._collections[collection_name] = Chroma(
                collection_name=collection_name,
                embedding_function=embedding_service.model,
                persist_directory=settings.CHROMA_PERSIST_DIR,
            )

        return self._collections[collection_name]

    def upsert_chunks(self, knowledge_base_id: int, document_id: int,
                      version_id: int, chunks: List[Document],
                      min_version_guard: int = None) -> int:
        """
        Upsert chunks for a document version with version guard.

        Uses deterministic chunk IDs based on kb/doc/version/index for idempotency.
        Only deletes versions older than min_version_guard to prevent stale data.

        Args:
            knowledge_base_id: The knowledge base ID
            document_id: The document ID
            version_id: The version being upserted
            chunks: The document chunks
            min_version_guard: Minimum version to preserve (prevents old overwriting new)

        Returns:
            Number of chunks upserted
        """
        collection = self._get_collection(knowledge_base_id)

        # VERSION PROTECTION: reject incoming versions older than the highest existing version.
        # This prevents a late-arriving old version from overwriting newer data.
        current_max_version = self._get_max_version_id(collection, document_id)
        if current_max_version is not None and version_id < current_max_version:
            logger.warning(
                f"Rejecting stale version: doc {document_id} incoming v{version_id} < current v{current_max_version}")
            return 0  # Do not write stale version

        # Delete only versions older than the incoming version (clean up old data).
        if version_id > 1:
            self.delete_versions_older_than(knowledge_base_id, document_id, version_id)

        if not chunks:
            return 0

        # Generate deterministic IDs and add metadata
        ids = []
        for idx, chunk in enumerate(chunks):
            chunk.metadata.update({
                "knowledge_base_id": knowledge_base_id,
                "document_id": document_id,
                "version_id": version_id,
                "chunk_index": idx,
            })
            # Deterministic ID for idempotency
            chunk_id = f"kb{knowledge_base_id}_doc{document_id}_v{version_id}_chunk{idx}"
            ids.append(chunk_id)

        # Add documents with explicit IDs
        collection.add_documents(documents=chunks, ids=ids)

        logger.info(f"Upserted {len(chunks)} chunks for doc {document_id} v{version_id}")
        return len(chunks)

    def _get_max_version_id(self, collection, document_id: int) -> int | None:
        """Get the highest version_id currently stored for a document, or None if no chunks exist."""
        result = collection.get(where={"document_id": {"$eq": document_id}}, include=["metadatas"])
        metadatas = result.get("metadatas", [])
        if not metadatas:
            return None
        return max((m.get("version_id", 0) for m in metadatas), default=None)

    def delete_versions_older_than(self, knowledge_base_id: int, document_id: int,
                                    min_version: int) -> int:
        """Delete chunks for versions older than min_version."""
        collection = self._get_collection(knowledge_base_id)

        result = collection.get(where={
            "$and": [
                {"document_id": {"$eq": document_id}},
                {"version_id": {"$lt": min_version}},
            ]
        })

        ids = result.get("ids", [])
        if ids:
            collection.delete(ids=ids)
            logger.info(f"Deleted {len(ids)} old chunks for doc {document_id} (< v{min_version})")

        return len(ids)

    def delete_document_version(self, knowledge_base_id: int, document_id: int,
                                version_id: int) -> int:
        """Delete all chunks for a specific document version."""
        collection = self._get_collection(knowledge_base_id)

        result = collection.get(where={
            "$and": [
                {"document_id": {"$eq": document_id}},
                {"version_id": {"$eq": version_id}},
            ]
        })

        ids = result.get("ids", [])
        if ids:
            collection.delete(ids=ids)
            logger.info(f"Deleted {len(ids)} chunks for doc {document_id} v{version_id}")

        return len(ids)

    def delete_document(self, knowledge_base_id: int, document_id: int) -> int:
        """Delete all chunks for a document (all versions)."""
        collection = self._get_collection(knowledge_base_id)

        result = collection.get(where={"document_id": {"$eq": document_id}})
        ids = result.get("ids", [])

        if ids:
            collection.delete(ids=ids)
            logger.info(f"Deleted {len(ids)} chunks for doc {document_id} (all versions)")

        return len(ids)

    def delete_knowledge_base(self, knowledge_base_id: int) -> int:
        """Delete all chunks for an entire knowledge base."""
        collection = self._get_collection(knowledge_base_id)

        result = collection.get(where={"knowledge_base_id": {"$eq": knowledge_base_id}})
        ids = result.get("ids", [])

        if ids:
            collection.delete(ids=ids)
            logger.info(f"Deleted {len(ids)} chunks for KB {knowledge_base_id}")

        return len(ids)

    def search(self, knowledge_base_id: int, query: str,
               visible_document_ids: List[int], top_k: int = None) -> List[Document]:
        """
        Search for relevant chunks, filtered by visible document IDs.

        Only returns chunks from the latest version of each document to prevent
        stale version data from being retrieved.

        Args:
            knowledge_base_id: The knowledge base to search in
            query: The search query
            visible_document_ids: List of document IDs the user can access (required)
            top_k: Number of results to return

        Returns:
            List of relevant document chunks
        """
        if not visible_document_ids:
            # Empty list means no visible documents - return empty results
            return []

        # Limit visible document IDs to prevent abuse
        if len(visible_document_ids) > 1000:
            visible_document_ids = visible_document_ids[:1000]

        # Limit top_k to prevent abuse
        top_k = min(top_k or settings.TOP_K, 50)

        collection = self._get_collection(knowledge_base_id)

        # Build filter for visible documents
        where_filter = {
            "$and": [
                {"knowledge_base_id": {"$eq": knowledge_base_id}},
                {"document_id": {"$in": visible_document_ids}},
            ]
        }

        # Retrieve more results than needed to allow for version filtering
        results = collection.similarity_search_with_score(
            query, k=top_k * 3, filter=where_filter
        )

        # First, find the maximum version for each document
        doc_max_version = {}
        for doc, score in results:
            doc_id = doc.metadata.get("document_id")
            version_id = doc.metadata.get("version_id", 0)

            if doc_id not in doc_max_version or version_id > doc_max_version[doc_id]:
                doc_max_version[doc_id] = version_id

        # Filter to only include chunks from the latest version of each document
        latest_version_chunks = []
        for doc, score in results:
            doc_id = doc.metadata.get("document_id")
            version_id = doc.metadata.get("version_id", 0)

            # Only keep chunks from the latest version
            if version_id == doc_max_version.get(doc_id, 0):
                latest_version_chunks.append((doc, score))

        # Sort by score (ascending for distance, descending for similarity)
        # Chroma returns cosine distance by default (smaller = more similar)
        latest_version_chunks.sort(key=lambda x: x[1])

        # Return top_k results
        return [doc for doc, score in latest_version_chunks[:top_k]]


# Singleton instance
vector_store = VectorStoreService()
