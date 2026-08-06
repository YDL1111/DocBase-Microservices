"""
Text chunking service.
"""
from typing import List

from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_core.documents import Document

from app.core.config import settings


class TextChunker:
    """Split documents into chunks for embedding."""

    def __init__(self):
        self.splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.CHUNK_SIZE,
            chunk_overlap=settings.CHUNK_OVERLAP,
            separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
        )

    def chunk_documents(self, documents: List[Document]) -> List[Document]:
        """Split documents into smaller chunks."""
        if not documents:
            return []

        chunks = self.splitter.split_documents(documents)

        # Add chunk index to metadata
        for idx, chunk in enumerate(chunks):
            chunk.metadata["chunk_index"] = idx

        return chunks

    def create_chunk_id(self, knowledge_base_id: int, document_id: int,
                        version_id: int, chunk_index: int) -> str:
        """Create a deterministic chunk ID."""
        return f"kb{knowledge_base_id}_doc{document_id}_v{version_id}_chunk{chunk_index}"
