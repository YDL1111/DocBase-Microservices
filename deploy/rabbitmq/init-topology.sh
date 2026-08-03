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

request PUT "/api/exchanges/${vhost}/docbase.document.exchange" \
  '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
request PUT "/api/exchanges/${vhost}/docbase.ingest.dlx" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

request PUT "/api/queues/${vhost}/docbase.ingest.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}'
request PUT "/api/queues/${vhost}/docbase.ingest.retry.30s.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":30000,"x-dead-letter-exchange":"docbase.document.exchange"}}'
request PUT "/api/queues/${vhost}/docbase.ingest.retry.5m.queue" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic","x-message-ttl":300000,"x-dead-letter-exchange":"docbase.document.exchange"}}'
request PUT "/api/queues/${vhost}/docbase.ingest.dlq" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}'

request POST "/api/bindings/${vhost}/e/docbase.document.exchange/q/docbase.ingest.queue" \
  '{"routing_key":"document.*","arguments":{}}'
request POST "/api/bindings/${vhost}/e/docbase.ingest.dlx/q/docbase.ingest.dlq" \
  '{"routing_key":"failed","arguments":{}}'

echo "RabbitMQ topology initialized"
