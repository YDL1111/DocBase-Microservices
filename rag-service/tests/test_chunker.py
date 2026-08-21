import pytest

from app.services.chunker import TextChunker
from app.services.document_block import NoExtractableTextError, ParsedBlock


def test_short_structured_block_stays_whole_and_gets_embedding_context():
    block = ParsedBlock(
        page_content="进入车间前需要完成安全培训。",
        block_type="prose",
        metadata={"document_title": "员工手册", "page": 3},
        heading_path=("安全规范",),
    )

    chunks = TextChunker().chunk_documents([block])

    assert len(chunks) == 1
    assert chunks[0].page_content.startswith("文档：员工手册\n章节：安全规范\n\n")
    assert chunks[0].metadata["page"] == 3
    assert chunks[0].metadata["block_type"] == "prose"
    assert chunks[0].metadata["chunk_index"] == 0


def test_long_prose_is_split_but_short_slide_is_not():
    long_prose = ParsedBlock("第一部分。" * 300, "prose")
    slide = ParsedBlock("本页只有一个完整结论。", "slide", metadata={"slide": 2})

    chunks = TextChunker().chunk_documents([long_prose, slide])

    prose_chunks = [chunk for chunk in chunks if chunk.metadata["block_type"] == "prose"]
    slide_chunks = [chunk for chunk in chunks if chunk.metadata["block_type"] == "slide"]
    assert len(prose_chunks) > 1
    assert len(slide_chunks) == 1
    assert slide_chunks[0].metadata["slide"] == 2


def test_table_chunks_repeat_the_header_without_row_overlap():
    header = "| 设备 | 状态 |\n| --- | --- |"
    rows = [f"| 设备-{index:03d} | 正常运行 |" for index in range(100)]
    block = ParsedBlock(
        page_content=header + "\n" + "\n".join(rows),
        block_type="table",
        metadata={"document_title": "设备清单"},
    )

    chunks = TextChunker().chunk_documents([block])

    assert len(chunks) > 1
    assert all("| 设备 | 状态 |\n| --- | --- |" in chunk.page_content for chunk in chunks)
    combined = "\n".join(chunk.page_content for chunk in chunks)
    assert all(combined.count(row) == 1 for row in rows)


def test_oversized_first_table_row_respects_chunk_size_without_overlap():
    header = "| 字段 | 内容 |\n| --- | --- |"
    oversized_row = "| 说明 | " + ("超长内容" * 300) + " |"
    block = ParsedBlock(header + "\n" + oversized_row, "table")

    chunks = TextChunker().chunk_documents([block])

    assert len(chunks) > 1
    assert all(chunk.metadata["raw_char_count"] <= TextChunker().chunk_size for chunk in chunks)
    reconstructed = "".join(
        chunk.page_content.split("| --- | --- |", 1)[1] for chunk in chunks
    )
    assert "".join(reconstructed.split()).count("超长内容") == 300


def test_oversized_table_header_without_data_rows_respects_chunk_size():
    markdown = "| " + ("wide-column | " * 120) + "\n| " + ("--- | " * 120)
    block = ParsedBlock(markdown, "table")

    chunks = TextChunker().chunk_documents([block])

    assert len(chunks) > 1
    assert all(chunk.metadata["raw_char_count"] <= TextChunker().chunk_size for chunk in chunks)


def test_empty_blocks_are_rejected():
    with pytest.raises(NoExtractableTextError, match="NO_EXTRACTABLE_TEXT"):
        TextChunker().chunk_documents([])
