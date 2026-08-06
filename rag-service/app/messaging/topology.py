"""
RabbitMQ topology constants.
"""
# Exchanges
KNOWLEDGE_EVENTS_EXCHANGE = "docbase.document.exchange"
INGEST_STATUS_EXCHANGE = "docbase.ingest.events"
RAG_RESULT_EXCHANGE = "docbase.rag.result.exchange"  # Dedicated exchange for RAG -> Ingest results
RETRY_DLX = "docbase.ingest.dlx"
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

# Routing Keys
RAG_INGEST_ROUTING_KEY = "rag.document.ingest.requested"
RAG_DELETE_ROUTING_KEY = "rag.document.delete.requested"
RAG_RESULT_ROUTING_PREFIX = "rag.result"
RETRY_ROUTING_KEYS = ["retry.1", "retry.2", "retry.3"]
FAILED_ROUTING_KEY = "failed"
