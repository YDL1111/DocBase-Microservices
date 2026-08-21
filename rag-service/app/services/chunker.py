"""Format-aware chunking for structured document blocks."""
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_core.documents import Document

from app.core.config import settings
from app.services.document_block import NoExtractableTextError, ParsedBlock


class TextChunker:
    """Create retrieval chunks while preserving document structure."""

    def __init__(self):
        if settings.CHUNK_OVERLAP < 0 or settings.CHUNK_OVERLAP >= settings.CHUNK_SIZE:
            raise ValueError("CHUNK_OVERLAP must be non-negative and smaller than CHUNK_SIZE")
        self.chunk_size = settings.CHUNK_SIZE
        self.chunk_overlap = settings.CHUNK_OVERLAP

    def chunk_documents(self, blocks: list[ParsedBlock]) -> list[Document]:
        if not blocks:
            raise NoExtractableTextError()

        chunks: list[Document] = []
        for block_index, block in enumerate(blocks):
            content = block.normalized_text()
            if not content:
                continue
            metadata = self._block_metadata(block, block_index)
            if block.block_type == "table":
                parts = self._split_table(content)
            elif len(content) <= self.chunk_size:
                parts = [content]
            else:
                parts = self._split_long_text(content)

            for chunk_in_block, part in enumerate(parts):
                if not part.strip():
                    continue
                chunk_metadata = dict(metadata)
                chunk_metadata["chunk_in_block"] = chunk_in_block
                chunk_metadata["raw_char_count"] = len(part)
                chunks.append(Document(
                    page_content=self._with_embedding_context(part, chunk_metadata),
                    metadata=chunk_metadata,
                ))

        if not chunks:
            raise NoExtractableTextError()
        for chunk_index, chunk in enumerate(chunks):
            chunk.metadata["chunk_index"] = chunk_index
        return chunks

    def _split_long_text(self, text: str) -> list[str]:
        return self._split_text(text, self.chunk_size, self.chunk_overlap)

    @staticmethod
    def _split_text(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
        splitter = RecursiveCharacterTextSplitter(
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""],
        )
        return splitter.split_text(text)

    def _split_table(self, markdown: str) -> list[str]:
        lines = [line.strip() for line in markdown.splitlines() if line.strip()]
        if len(markdown) <= self.chunk_size:
            return [markdown]

        header = lines[:2]
        header_text = "\n".join(header)
        if len(header_text) >= self.chunk_size:
            return self._split_text(markdown, self.chunk_size, 0)
        if len(lines) <= 2:
            return [markdown]
        row_budget = max(1, self.chunk_size - len(header_text) - 1)
        rows = lines[2:]
        parts: list[str] = []
        current = list(header)
        for row in rows:
            candidate = "\n".join(current + [row])
            if len(candidate) <= self.chunk_size:
                current.append(row)
                continue
            if len(current) > len(header):
                parts.append("\n".join(current))
            current = list(header)
            if len(row) > row_budget:
                row_parts = self._split_text(row, row_budget, 0)
                for row_part in row_parts:
                    parts.append("\n".join(header + [row_part]))
            else:
                current.append(row)
        if len(current) > len(header):
            parts.append("\n".join(current))
        return parts or [markdown]

    @staticmethod
    def _block_metadata(block: ParsedBlock, block_index: int) -> dict:
        metadata = dict(block.metadata)
        metadata["block_type"] = block.block_type
        metadata["block_index"] = block_index
        if block.heading_path:
            metadata["heading_path"] = " > ".join(block.heading_path)
        return metadata

    @staticmethod
    def _with_embedding_context(content: str, metadata: dict) -> str:
        prefixes = []
        title = str(metadata.get("document_title") or "").strip()
        heading_path = str(metadata.get("heading_path") or "").strip()
        if title:
            prefixes.append(f"文档：{title}")
        if heading_path and heading_path != title:
            prefixes.append(f"章节：{heading_path}")
        if not prefixes:
            return content
        return "\n".join(prefixes) + "\n\n" + content

    @staticmethod
    def create_chunk_id(knowledge_base_id: int, document_id: int,
                        version_id: int, chunk_index: int) -> str:
        return f"kb{knowledge_base_id}_doc{document_id}_v{version_id}_chunk{chunk_index}"
