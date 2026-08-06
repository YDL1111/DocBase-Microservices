"""
Tests for document parsing.
"""
import pytest
from app.services.parser import DocumentParser


@pytest.fixture
def parser():
    return DocumentParser()


def test_get_file_type(parser):
    """Test file type detection."""
    assert parser.get_file_type("test.pdf") == "pdf"
    assert parser.get_file_type("test.docx") == "word"
    assert parser.get_file_type("test.xlsx") == "excel"
    assert parser.get_file_type("test.txt") == "text"
    assert parser.get_file_type("test.unknown") == "unknown"


def test_parse_text(parser, tmp_path):
    """Test parsing a text file."""
    text_file = tmp_path / "test.txt"
    text_file.write_text("Hello, this is a test document.\nIt has multiple lines.")

    docs, meta = parser.parse(str(text_file))

    assert len(docs) == 1
    assert "Hello, this is a test document" in docs[0].page_content
    assert meta["file_type"] == "text"
    assert meta["filename"] == "test.txt"
