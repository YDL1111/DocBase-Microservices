"""Wire payload builder for RAG results consumed by ingest-service."""

from datetime import datetime


def build_result_payload(event, event_type: str, success: bool, **extra) -> dict:
    payload = {
        "eventId": None,
        "eventType": event_type,
        "aggregateType": "ingest_task",
        "aggregateId": event.aggregate_id,
        "knowledgeBaseId": event.knowledge_base_id,
        "documentId": event.document_id,
        "versionId": event.version_id,
        "status": "SUCCEEDED" if success else "FAILED",
        "operatorId": event.operator_id,
        "schemaVersion": 1,
        "occurredAt": datetime.utcnow().isoformat(),
    }
    payload.update(extra)
    return payload
