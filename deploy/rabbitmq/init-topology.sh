#!/bin/sh
set -eu

base_url="${RABBITMQ_MANAGEMENT_URL:-http://rabbitmq:15672}"
username="${RABBITMQ_USER:-docbase}"
password="${RABBITMQ_PASSWORD:-change-me-rabbitmq}"
vhost="%2Fdocbase"

request() {
  method="$1"
  path="$2"
  payload="$3"
  curl -fsS -u "${username}:${password}" \
    -H "content-type: application/json" \
    -X "${method}" \
    "${base_url}${path}" \
    -d "${payload}" >/dev/null
}

attempt=0
until curl -fsS -u "${username}:${password}" "${base_url}/api/health/checks/alarms" >/dev/null; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 30 ]; then
    echo "RabbitMQ Management API did not become ready" >&2
    exit 1
  fi
  sleep 2
done

# =============================================================================
# Exchanges
# =============================================================================

# Knowledge events exchange (consumed by ingest-service)
request PUT "/api/exchanges/${vhost}/docbase.document.exchange" \
  '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

# Ingest status events exchange (published by ingest-service, consumed by knowledge-service)
request PUT "/api/exchanges/${vhost}/docbase.ingest.events" \
  '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

# Dead letter exchange for ingest retries
request PUT "/api/exchanges/${vhost}/docbase.ingest.dlx" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

# =============================================================================
# Queues
# =============================================================================

# Main ingest queue - routes to DLX on rejection with fixed routing key for DLQ
request PUT "/api/queues/${vhost}/docbase.ingest.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"docbase.ingest.dlx","x-dead-letter-routing-key":"failed"}}'

# First retry queue: 30 seconds TTL, dead-letters back to main exchange
request PUT "/api/queues/${vhost}/docbase.ingest.retry.30s.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":30000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"document.retry"}}'

# Second retry queue: 5 minutes TTL, dead-letters back to main exchange
request PUT "/api/queues/${vhost}/docbase.ingest.retry.5m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":300000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"document.retry"}}'

# Third retry queue: 30 minutes TTL, dead-letters back to main exchange
request PUT "/api/queues/${vhost}/docbase.ingest.retry.30m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":1800000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"document.retry"}}'

# Dead letter queue for permanently failed messages
request PUT "/api/queues/${vhost}/docbase.ingest.dlq" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}'

# Knowledge status queue (consumed by knowledge-service)
request PUT "/api/queues/${vhost}/docbase.knowledge.status.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}'

# =============================================================================
# Bindings
# =============================================================================

# Main ingest queue listens for document events
request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.ingest.queue" \
  '{"routing_key":"document.*","arguments":{}}'

# Main ingest queue also listens for retry events (from retry queues)
request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.ingest.queue" \
  '{"routing_key":"document.retry","arguments":{}}'

# Retry queues bind to DLX with specific routing keys
request POST "/api/bindings/${vhost}/e/docbase.ingest.dlx/q/docbase.ingest.retry.30s.queue" \
  '{"routing_key":"retry.1","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.ingest.dlx/q/docbase.ingest.retry.5m.queue" \
  '{"routing_key":"retry.2","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.ingest.dlx/q/docbase.ingest.retry.30m.queue" \
  '{"routing_key":"retry.3","arguments":{}}'

# DLQ binds to DLX for permanently failed messages
request POST "/api/bindings/${vhost}/e/docbase.ingest.dlx/q/docbase.ingest.dlq" \
  '{"routing_key":"failed","arguments":{}}'

# Knowledge status queue listens for ingest status events
request POST "/api/bindings/${vhost}/e/docbase.ingest.events/q/docbase.knowledge.status.queue" \
  '{"routing_key":"ingest.document.*","arguments":{}}'

echo "RabbitMQ topology initialized"
