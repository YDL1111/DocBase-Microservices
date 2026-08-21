"""Structured intermediate representation for parsed document content."""
from dataclasses import dataclass, field
from typing import Any


class NoExtractableTextError(ValueError):
    """Raised when an uploaded document contains no searchable text."""

    code = "NO_EXTRACTABLE_TEXT"

    def __init__(self, message: str = "Document contains no extractable text"):
        super().__init__(f"{self.code}: {message}")


@dataclass(slots=True)
class ParsedBlock:
    """A structure-aware unit produced by a format-specific parser."""

    page_content: str
    block_type: str
    metadata: dict[str, Any] = field(default_factory=dict)
    heading_path: tuple[str, ...] = ()

    def normalized_text(self) -> str:
        return self.page_content.strip()

