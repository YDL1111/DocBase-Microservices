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
# VHost
# =============================================================================

# Create the vhost (required before any queue/exchange can be created in it)
echo "Creating vhost: $vhost"
curl -fsS -u "${username}:${password}" -X PUT "${base_url}/api/vhosts/${vhost}" \
  --data '{}' >/dev/null 2>&1 || echo "  [WARN] VHost may already exist"

# Set permissions for docbase user on this vhost
curl -fsS -u "${username}:${password}" -X PUT \
  "${base_url}/api/permissions/${vhost}/docbase" \
  --data '{"configure":".*","write":".*","read":".*"}' >/dev/null 2>&1 || true

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
# Already has x-dead-letter-routing-key=failed - basicReject(false) will reach DLQ
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
# RAG Consumer Retry Topology (independent from Ingest)
# =============================================================================
# NOTE: Migration cleanup for old RAG retry queues (that used shared document.retry)
# is a ONE-TIME operation. It has been moved to a separate migration script
# (migrate-rag-retry-topology.sh) and should NOT be run on every startup, as it
# would delete queues with pending messages.

# RAG retry DLX (direct exchange for retry routing)
request PUT "/api/exchanges/${vhost}/docbase.rag.retry.dlx" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

# RAG main consumer queue (consumes knowledge events for RAG processing)
request PUT "/api/queues/${vhost}/docbase.rag.ingest.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"docbase.rag.retry.dlx","x-dead-letter-routing-key":"rag.failed"}}'

# RAG retry queues - TTL expires, dead-letters back to main exchange with RAG-only routing key
request PUT "/api/queues/${vhost}/docbase.rag.retry.30s.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":30000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"rag.document.retry"}}'
request PUT "/api/queues/${vhost}/docbase.rag.retry.5m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":300000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"rag.document.retry"}}'
request PUT "/api/queues/${vhost}/docbase.rag.retry.30m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":1800000,"x-dead-letter-exchange":"docbase.document.exchange","x-dead-letter-routing-key":"rag.document.retry"}}'

# RAG DLQ
request PUT "/api/queues/${vhost}/docbase.rag.dlq" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}'

# RAG consumer queue bindings
request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.rag.ingest.queue" \
  '{"routing_key":"rag.document.ingest.requested","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.rag.ingest.queue" \
  '{"routing_key":"rag.document.delete.requested","arguments":{}}'
# CRITICAL: RAG queue binds to RAG-only retry routing key (NOT shared document.retry)
request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.rag.ingest.queue" \
  '{"routing_key":"rag.document.retry","arguments":{}}'

# RAG retry queues bind to RAG retry DLX
request POST "/api/bindings/${vhost}/e/docbase.rag.retry.dlx/q/docbase.rag.retry.30s.queue" \
  '{"routing_key":"rag.retry.1","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.rag.retry.dlx/q/docbase.rag.retry.5m.queue" \
  '{"routing_key":"rag.retry.2","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.rag.retry.dlx/q/docbase.rag.retry.30m.queue" \
  '{"routing_key":"rag.retry.3","arguments":{}}'

# RAG DLQ binds to RAG retry DLX
request POST "/api/bindings/${vhost}/e/docbase.rag.retry.dlx/q/docbase.rag.dlq" \
  '{"routing_key":"rag.failed","arguments":{}}'

# =============================================================================
# RAG Result Exchange (separate from Ingest status events)
# =============================================================================

# RAG result exchange - dedicated exchange for RAG -> Ingest results
request PUT "/api/exchanges/${vhost}/docbase.rag.result.exchange" \
  '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

# RAG result queue (consumed by ingest-service)
# Uses dedicated exchange and routing prefix to avoid consuming Ingest's own status events
request PUT "/api/queues/${vhost}/docbase.rag.result.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"docbase.rag.result.dlx","x-dead-letter-routing-key":"failed"}}'

# RAG result retry queues - TTL expires, dead-letters back to RAG result exchange
request PUT "/api/queues/${vhost}/docbase.rag.result.retry.30s.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":30000,"x-dead-letter-exchange":"docbase.rag.result.exchange","x-dead-letter-routing-key":"rag.result.retry"}}'
request PUT "/api/queues/${vhost}/docbase.rag.result.retry.5m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":300000,"x-dead-letter-exchange":"docbase.rag.result.exchange","x-dead-letter-routing-key":"rag.result.retry"}}'

# RAG result DLX and DLQ
request PUT "/api/exchanges/${vhost}/docbase.rag.result.dlx" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
request PUT "/api/queues/${vhost}/docbase.rag.result.dlq" \
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

# =============================================================================
# Migration: Remove old bindings (idempotent - safe to run on existing volumes)
# =============================================================================

# Delete old binding: docbase.ingest.events -> docbase.rag.result.queue (ingest.document.*)
# This was the previous incorrect binding that caused RAG result queue to consume Ingest status events
curl -fsS -u "${username}:${password}" -X DELETE \
  "${base_url}/api/bindings/${vhost}/e/docbase.ingest.events/q/docbase.rag.result.queue/ingest.document.%2A" \
  >/dev/null 2>&1 || true  # Ignore errors if binding doesn't exist

# =============================================================================
# Bindings
# =============================================================================

# Knowledge status queue listens for ingest status events
request POST "/api/bindings/${vhost}/e/docbase.ingest.events/q/docbase.knowledge.status.queue" \
  '{"routing_key":"ingest.document.*","arguments":{}}'

# RAG result queue listens for RAG completion events (dedicated routing prefix)
request POST "/api/bindings/${vhost}/e/docbase.rag.result.exchange/q/docbase.rag.result.queue" \
  '{"routing_key":"rag.result.*","arguments":{}}'

# RAG result queue also listens for retry events (from retry queues)
request POST "/api/bindings/${vhost}/e/docbase.rag.result.exchange/q/docbase.rag.result.queue" \
  '{"routing_key":"rag.result.retry","arguments":{}}'

# RAG result retry queues bind to DLX with specific routing keys
request POST "/api/bindings/${vhost}/e/docbase.rag.result.dlx/q/docbase.rag.result.retry.30s.queue" \
  '{"routing_key":"retry.1","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.rag.result.dlx/q/docbase.rag.result.retry.5m.queue" \
  '{"routing_key":"retry.2","arguments":{}}'

# RAG result DLQ binds to DLX for permanently failed messages
request POST "/api/bindings/${vhost}/e/docbase.rag.result.dlx/q/docbase.rag.result.dlq" \
  '{"routing_key":"failed","arguments":{}}'

echo "RabbitMQ topology initialized"
