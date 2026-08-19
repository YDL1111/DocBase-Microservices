"""RAG result payloads must use the camelCase fields consumed by ingest-service."""

from types import SimpleNamespace

from app.services.result_payload import build_result_payload


def _event():
    return SimpleNamespace(
        aggregate_id="1",
        knowledge_base_id=1,
        document_id=1,
        version_id=1,
        operator_id=1,
    )


def test_completed_result_uses_chunk_count_contract_field():
    payload = build_result_payload(
        _event(), "rag.document.ingest.completed", True, chunkCount=7
    )
    assert payload["chunkCount"] == 7
    assert "chunk_count" not in payload


def test_failed_result_uses_error_message_contract_field():
    payload = build_result_payload(
        _event(), "rag.document.ingest.failed", False,
        errorMessage="Invalid object key"
    )
    assert payload["errorMessage"] == "Invalid object key"
    assert "error_message" not in payload
