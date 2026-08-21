"""
Vector store service using ChromaDB.
"""
import os
from dataclasses import dataclass
from typing import List, Optional, Dict, Any

from langchain_chroma import Chroma
from langchain_core.documents import Document

from app.core.config import settings
from app.core.logging import get_logger
from app.services.embedding import embedding_service

logger = get_logger(__name__)


@dataclass(frozen=True)
class ScoredDocument:
    """A retrieved chunk with its first-stage relevance and stored embedding."""

    document: Document
    relevance_score: float
    distance: float
    embedding: tuple[float, ...]


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
                embedding_function=embedding_service,
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
            chunk.metadata["chunk_id"] = chunk_id
            ids.append(chunk_id)

        # Add documents with explicit IDs
        collection.add_documents(documents=chunks, ids=ids)

        # Recheck after the write. A newer version may have arrived between the
        # optimistic pre-check and this upsert on another worker.
        current_max_version = self._get_max_version_id(collection, document_id)
        if current_max_version is not None and version_id < current_max_version:
            self._delete_version_chunks(collection, document_id, version_id)
            logger.warning(
                "Discarded concurrently superseded version: doc %d incoming v%d < current v%d",
                document_id, version_id, current_max_version,
            )
            return 0

        self._delete_stale_chunk_ids(collection, document_id, version_id, set(ids))

        # Keep the previous searchable version until the new write has completed.
        if version_id > 1:
            self.delete_versions_older_than(knowledge_base_id, document_id, version_id)

        logger.info(f"Upserted {len(chunks)} chunks for doc {document_id} v{version_id}")
        return len(chunks)

    @staticmethod
    def _delete_stale_chunk_ids(collection, document_id: int, version_id: int,
                                current_ids: set[str]) -> int:
        """Remove trailing chunks left by an older split of the same version."""
        existing = collection.get(where={
            "$and": [
                {"document_id": {"$eq": document_id}},
                {"version_id": {"$eq": version_id}},
            ]
        })
        stale_ids = [chunk_id for chunk_id in existing.get("ids", []) if chunk_id not in current_ids]
        if stale_ids:
            collection.delete(ids=stale_ids)
            logger.info(
                "Deleted %d stale chunks after re-splitting doc %d v%d",
                len(stale_ids), document_id, version_id,
            )
        return len(stale_ids)

    @staticmethod
    def _delete_version_chunks(collection, document_id: int, version_id: int) -> int:
        result = collection.get(where={
            "$and": [
                {"document_id": {"$eq": document_id}},
                {"version_id": {"$eq": version_id}},
            ]
        })
        ids = result.get("ids", [])
        if ids:
            collection.delete(ids=ids)
        return len(ids)

    def _get_max_version_id(self, collection, document_id: int) -> int | None:
        """Get the highest version_id currently stored for a document, or None if no chunks exist."""
        result = collection.get(where={"document_id": {"$eq": document_id}}, include=["metadatas"])
        metadatas = result.get("metadatas", [])
        if not metadatas:
            return None
        return max((m.get("version_id", 0) for m in metadatas), default=None)

    @staticmethod
    def _get_max_versions(collection, document_ids: set[int]) -> dict[int, int]:
        if not document_ids:
            return {}
        result = collection.get(
            where={"document_id": {"$in": sorted(document_ids)}},
            include=["metadatas"],
        )
        max_versions: dict[int, int] = {}
        for metadata in result.get("metadatas", []):
            document_id = metadata.get("document_id")
            version_id = metadata.get("version_id", 0)
            if document_id is None:
                continue
            max_versions[document_id] = max(max_versions.get(document_id, 0), version_id)
        return max_versions

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
        """Backward-compatible document-only search."""
        return [candidate.document for candidate in self.search_candidates(
            knowledge_base_id, query, visible_document_ids, top_k
        )]

    def search_candidates(self, knowledge_base_id: int, query: str,
                          visible_document_ids: List[int],
                          candidate_k: int = None) -> List[ScoredDocument]:
        """
        Search candidate chunks and preserve relevance scores and embeddings.

        Only returns chunks from the latest version of each document to prevent
        stale version data from being retrieved.

        Args:
            knowledge_base_id: The knowledge base to search in
            query: The search query
            visible_document_ids: List of document IDs the user can access (required)
            candidate_k: Number of first-stage candidates to return

        Returns:
            Scored candidate chunks for global ranking and MMR
        """
        if not visible_document_ids:
            # Empty list means no visible documents - return empty results
            return []

        # Limit visible document IDs to prevent abuse
        if len(visible_document_ids) > 1000:
            visible_document_ids = visible_document_ids[:1000]

        candidate_k = min(candidate_k or settings.RETRIEVAL_CANDIDATE_K, 100)

        collection = self._get_collection(knowledge_base_id)

        # Build filter for visible documents
        where_filter = {
            "$and": [
                {"knowledge_base_id": {"$eq": knowledge_base_id}},
                {"document_id": {"$in": visible_document_ids}},
            ]
        }

        query_embedding = embedding_service.embed_query(query)
        raw_results = collection._collection.query(
            query_embeddings=[query_embedding],
            n_results=candidate_k * 3,
            where=where_filter,
            include=["documents", "metadatas", "distances", "embeddings"],
        )

        ids = (raw_results.get("ids") or [[]])[0]
        documents = (raw_results.get("documents") or [[]])[0]
        metadatas = (raw_results.get("metadatas") or [[]])[0]
        distances = (raw_results.get("distances") or [[]])[0]
        embeddings = raw_results.get("embeddings")
        embeddings = embeddings[0] if embeddings is not None and len(embeddings) else []

        candidates: list[ScoredDocument] = []
        for index, text in enumerate(documents):
            metadata = dict(metadatas[index] or {})
            if index < len(ids):
                metadata.setdefault("chunk_id", ids[index])
            distance = float(distances[index]) if index < len(distances) else float("inf")
            embedding = embeddings[index] if index < len(embeddings) else []
            candidates.append(ScoredDocument(
                document=Document(page_content=text or "", metadata=metadata),
                relevance_score=1.0 / (1.0 + max(distance, 0.0)),
                distance=distance,
                embedding=tuple(float(value) for value in embedding),
            ))

        candidate_document_ids = {
            candidate.document.metadata.get("document_id") for candidate in candidates
            if isinstance(candidate.document.metadata.get("document_id"), int)
        }
        # Resolve latest versions independently of semantic candidates. Querying
        # after the vector search prevents a concurrently inserted newer version
        # from making an older candidate look current.
        doc_max_version = self._get_max_versions(collection, candidate_document_ids)

        # Filter to only include chunks from the latest version of each document
        latest_version_chunks = []
        for candidate in candidates:
            doc_id = candidate.document.metadata.get("document_id")
            version_id = candidate.document.metadata.get("version_id", 0)

            # Only keep chunks from the latest version
            if version_id == doc_max_version.get(doc_id, 0):
                latest_version_chunks.append(candidate)

        latest_version_chunks.sort(key=lambda item: item.relevance_score, reverse=True)

        return latest_version_chunks[:candidate_k]


# Singleton instance
vector_store = VectorStoreService()
