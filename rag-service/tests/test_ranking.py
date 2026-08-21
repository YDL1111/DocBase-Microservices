from langchain_core.documents import Document

from app.services.rag import RAGService
from app.services.vector_store import ScoredDocument


def candidate(chunk_id, document_id, chunk_index, score, embedding, text=None):
    return ScoredDocument(
        document=Document(
            page_content=text or f"chunk {chunk_id}",
            metadata={
                "chunk_id": chunk_id,
                "document_id": document_id,
                "chunk_index": chunk_index,
                "file_name": f"doc-{document_id}.txt",
            },
        ),
        relevance_score=score,
        distance=(1.0 / score) - 1.0,
        embedding=tuple(embedding),
    )


def test_adjacent_near_duplicate_keeps_the_more_relevant_chunk():
    service = RAGService()
    first = candidate("a", 1, 0, 0.9, (1.0, 0.0))
    duplicate = candidate("b", 1, 1, 0.8, (0.999, 0.001))
    distinct = candidate("c", 1, 3, 0.7, (0.0, 1.0))

    ranked = service._rank_candidates([duplicate, distinct, first], 3)

    assert [item.document.metadata["chunk_id"] for item in ranked] == ["a", "c"]


def test_mmr_prefers_diversity_over_a_redundant_second_result():
    service = RAGService()
    first = candidate("a", 1, 0, 0.9, (1.0, 0.0))
    redundant = candidate("b", 2, 0, 0.89, (0.999, 0.001))
    diverse = candidate("c", 3, 0, 0.7, (0.0, 1.0))

    ranked = service._rank_candidates([first, redundant, diverse], 2)

    assert [item.document.metadata["chunk_id"] for item in ranked] == ["a", "c"]


def test_context_budget_only_includes_whole_chunks():
    results = [
        {"file_name": "too-long.txt", "content": "A" * 200, "document_id": 1},
        {"file_name": "fits.txt", "content": "完整片段", "document_id": 2},
    ]

    context, included = RAGService._pack_context(results, 80)

    assert "A" * 20 not in context
    assert "完整片段" in context
    assert [item["document_id"] for item in included] == [2]

