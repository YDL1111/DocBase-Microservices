"""
RabbitMQ topology constants.
"""
# Exchanges
KNOWLEDGE_EVENTS_EXCHANGE = "docbase.document.exchange"
INGEST_STATUS_EXCHANGE = "docbase.ingest.events"
RAG_RESULT_EXCHANGE = "docbase.rag.result.exchange"  # Dedicated exchange for RAG -> Ingest results
# CRITICAL: RAG uses its OWN dead-letter exchange, separate from Ingest's docbase.ingest.dlx.
# Sharing a DLX would cause retry messages to be routed to the wrong service's queues.
RETRY_DLX = "docbase.rag.retry.dlx"
RAG_RESULT_DLX = "docbase.rag.result.dlx"  # Dead letter exchange for RAG result retries

# Queues
RAG_CONSUMER_QUEUE = "docbase.rag.ingest.queue"
RAG_RESULT_QUEUE = "docbase.rag.result.queue"
RETRY_30S_QUEUE = "docbase.rag.retry.30s.queue"
RETRY_5M_QUEUE = "docbase.rag.retry.5m.queue"
RETRY_30M_QUEUE = "docbase.rag.retry.30m.queue"
DLQ_QUEUE = "docbase.rag.dlq"
RAG_RESULT_RETRY_30S_QUEUE = "docbase.rag.result.retry.30s.queue"
RAG_RESULT_RETRY_5M_QUEUE = "docbase.rag.result.retry.5m.queue"
RAG_RESULT_DLQ = "docbase.rag.result.dlq"  # Dead letter queue for RAG results

# Routing Keys — RAG uses its OWN routing keys, separate from Ingest's retry.1/retry.2/retry.3.
RAG_INGEST_ROUTING_KEY = "rag.document.ingest.requested"
RAG_DELETE_ROUTING_KEY = "rag.document.delete.requested"
RAG_RESULT_ROUTING_PREFIX = "rag.result"
RETRY_ROUTING_KEYS = ["rag.retry.1", "rag.retry.2", "rag.retry.3"]
FAILED_ROUTING_KEY = "rag.failed"
# CRITICAL: Retry queues must dead-letter back to a RAG-only routing key, NOT document.retry
# (which is shared with Ingest). This ensures retry messages only return to RAG main queue.
RAG_RETRY_ROUTING_KEY = "rag.document.retry"
