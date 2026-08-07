"""
Event handler - processes incoming knowledge events.
"""
import logging
from datetime import datetime

from app.core.config import settings
from app.db.session import SessionLocal
from app.db.models import RagDocument, ConsumedEvent, RagOutbox
from app.messaging.contracts import KnowledgeEvent
from app.services.ingestion import ingestion_service
from app.services.object_storage import object_storage

logger = logging.getLogger(__name__)


class EventHandler:
    """Handles incoming knowledge events from RabbitMQ."""

    async def handle_event(self, event: KnowledgeEvent):
        """
        Process a knowledge event idempotently.

        Args:
            event: The knowledge event to process
        """
        db = SessionLocal()
        try:
            # Check idempotency
            existing = db.query(ConsumedEvent).filter(
                ConsumedEvent.event_id == event.event_id
            ).first()

            if existing:
                logger.info(f"Event already consumed, skipping: {event.event_id}")
                return

            # Process based on event type
            if event.event_type == "rag.document.ingest.requested":
                await self._handle_ingest(event, db)
            elif event.event_type == "rag.document.delete.requested":
                await self._handle_delete(event, db)
            else:
                logger.warning(f"Unknown event type: {event.event_type}")
                # Record as consumed to avoid reprocessing
                self._record_consumed(event, db, "REJECTED", f"Unknown event type: {event.event_type}")
                return

            # Record successful consumption
            self._record_consumed(event, db, "SUCCESS")

        except Exception as e:
            logger.error(f"Failed to process event {event.event_id}: {e}")
            # Record failed consumption
            self._record_consumed(event, db, "FAILED", str(e)[:500])
            raise
        finally:
            db.close()

    async def _handle_ingest(self, event: KnowledgeEvent, db: SessionLocal):
        """Handle document ingestion request."""
        logger.info(f"Processing ingest request: doc={event.document_id}, kb={event.knowledge_base_id}")

        # Create or update document record
        doc = db.query(RagDocument).filter(
            RagDocument.event_id == event.event_id
        ).first()

        if not doc:
            doc = RagDocument()
            doc.event_id = event.event_id
            doc.knowledge_base_id = event.knowledge_base_id
            doc.document_id = event.document_id
            doc.version_id = event.version_id
            doc.object_key = event.object_key
            doc.file_name = event.file_name or "unknown"
            doc.content_type = event.content_type
            doc.status = "PROCESSING"
            db.add(doc)
            db.flush()

        try:
            # Process document
            result = await ingestion_service.ingest_document(
                knowledge_base_id=event.knowledge_base_id,
                document_id=event.document_id,
                version_id=event.version_id,
                object_key=event.object_key,
                file_name=event.file_name or "unknown",
                content_type=event.content_type,
            )

            # Update document status
            doc.status = "COMPLETED"
            doc.chunk_count = result["chunk_count"]
            doc.content_hash = result["content_hash"]
            doc.chroma_collection = f"{settings.CHROMA_COLLECTION_PREFIX}_kb{event.knowledge_base_id}"
            db.commit()

            # Publish completion event
            self._publish_result(event, db, "rag.document.ingest.completed", True,
                               chunk_count=result["chunk_count"])

        except Exception as e:
            logger.error(f"Ingestion failed for doc {event.document_id}: {e}")
            doc.status = "FAILED"
            doc.error_message = str(e)[:500]
            db.commit()

            # Publish failure event
            self._publish_result(event, db, "rag.document.ingest.failed", False,
                               error_message=str(e)[:500])

    async def _handle_delete(self, event: KnowledgeEvent, db: SessionLocal):
        """Handle document deletion request."""
        logger.info(f"Processing delete request: doc={event.document_id}, kb={event.knowledge_base_id}")

        try:
            # Delete from vector store
            deleted_count = await ingestion_service.delete_document(
                knowledge_base_id=event.knowledge_base_id,
                document_id=event.document_id,
            )

            # Update document status
            docs = db.query(RagDocument).filter(
                RagDocument.knowledge_base_id == event.knowledge_base_id,
                RagDocument.document_id == event.document_id,
            ).all()

            for doc in docs:
                doc.status = "DELETED"

            db.commit()

            # Publish completion event
            self._publish_result(event, db, "rag.document.delete.completed", True)

        except Exception as e:
            logger.error(f"Deletion failed for doc {event.document_id}: {e}")
            db.rollback()

            # Publish failure event
            self._publish_result(event, db, "rag.document.delete.failed", False,
                               error_message=str(e)[:500])

    def _record_consumed(self, event: KnowledgeEvent, db: SessionLocal,
                        result: str, error_message: str = None):
        """Record event consumption for idempotency."""
        consumed = ConsumedEvent()
        consumed.event_id = event.event_id
        consumed.event_type = event.event_type
        consumed.schema_version = event.schema_version
        consumed.result = result
        consumed.error_message = error_message
        consumed.consumed_at = datetime.utcnow()
        db.add(consumed)
        db.commit()

    def _publish_result(self, event: KnowledgeEvent, db: SessionLocal,
                       event_type: str, success: bool, **kwargs):
        """Publish a result event to the outbox."""
        import uuid
        import json

        outbox_event = RagOutbox()
        outbox_event.event_id = str(uuid.uuid4())
        outbox_event.event_type = event_type
        outbox_event.aggregate_type = "ingest_task"
        # CRITICAL: aggregate_id must be the ingest task ID (event.aggregate_id),
        # NOT the document_id. Ingest uses this to correlate the result back to the task.
        outbox_event.aggregate_id = event.aggregate_id
        outbox_event.knowledge_base_id = event.knowledge_base_id
        outbox_event.document_id = event.document_id

        # Build payload
        payload = {
            "eventId": outbox_event.event_id,
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
        payload.update(kwargs)

        outbox_event.payload = json.dumps(payload)
        outbox_event.status = "PENDING"
        outbox_event.retry_count = 0
        outbox_event.schema_version = 1
        outbox_event.created_at = datetime.utcnow()

        db.add(outbox_event)
        db.commit()
