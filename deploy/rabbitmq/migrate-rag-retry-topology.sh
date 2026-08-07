#!/bin/sh
# =============================================================================
# ONE-TIME MIGRATION: Migrate RAG retry topology to independent routing
# =============================================================================
# WARNING: This script DELETES queues. Run ONLY when:
#   1. RAG service is STOPPED (no consumers active)
#   2. Queues have NO messages that need to be preserved
#
# Safe to run idempotently: checks existence before deletion.
# After running this once, the regular init-topology.sh will create the new topology.
#
# Usage:
#   ./migrate-rag-retry-topology.sh
#
# Rollback: Restart services with old volumes (if backed up) or re-register documents.
# =============================================================================

set -eu

# Configuration (match compose.yml environment)
RABBITMQ_MANAGEMENT_URL="${RABBITMQ_MANAGEMENT_URL:-http://localhost:15672}"
RABBITMQ_USER="${RABBITMQ_USER:-docbase}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-change-me-rabbitmq}"
RABBITMQ_VHOST="${RABBITMQ_VHOST:-/docbase}"

# URL-encode vhost for API calls (/docbase -> %2Fdocbase)
VHOST_ENCODED=$(echo "$RABBITMQ_VHOST" | sed 's|/|%2F|g')

username="$RABBITMQ_USER"
password="$RABBITMQ_PASSWORD"
# CRITICAL: Do NOT append /api here — curl calls append /api/queues etc.
base_url="$RABBITMQ_MANAGEMENT_URL"

echo "=== RAG Retry Topology Migration ==="
echo "Target: $RABBITMQ_MANAGEMENT_URL vhost=$RABBITMQ_VHOST"
echo ""

# -----------------------------------------------------------------------------
# Step 1: Check queue status before deletion
# -----------------------------------------------------------------------------
check_queue() {
  local queue_name="$1"
  local info
  # CRITICAL: Use if/else instead of || to avoid set -e terminating on non-zero exit
  if info=$(curl -fsS -u "${username}:${password}" \
    "${base_url}/api/queues/${VHOST_ENCODED}/${queue_name}" 2>/dev/null); then
    : # success, continue
  else
    echo "  [SKIP] Queue '$queue_name' does not exist"
    return 0
  fi

  local messages
  local consumers
  messages=$(echo "$info" | grep -o '"messages":[0-9]*' | head -1 | cut -d: -f2)
  consumers=$(echo "$info" | grep -o '"consumers":[0-9]*' | head -1 | cut -d: -f2)
  messages=${messages:-0}
  consumers=${consumers:-0}

  echo "  Queue '$queue_name': messages=$messages consumers=$consumers"

  if [ "$messages" -gt 0 ]; then
    echo "  [WARN] Queue has $messages messages! These will be DELETED."
  fi
  if [ "$consumers" -gt 0 ]; then
    echo "  [WARN] Queue has $consumers active consumers! Stop RAG service first."
  fi
  return 0
}

echo "Step 1: Checking existing RAG retry queues..."
for queue in docbase.rag.retry.30s.queue docbase.rag.retry.5m.queue docbase.rag.retry.30m.queue docbase.rag.ingest.queue; do
  check_queue "$queue"
done
echo ""

# -----------------------------------------------------------------------------
# Step 2: Confirm deletion (unless AUTO_CONFIRM=true)
# -----------------------------------------------------------------------------
if [ "${AUTO_CONFIRM:-false}" != "true" ]; then
  echo "This will DELETE the old RAG retry queues listed above."
  echo "Ensure RAG service is STOPPED and no messages need preservation."
  echo "Set AUTO_CONFIRM=true to skip this prompt."
  printf "Continue? [y/N] "
  read -r confirm
  if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "Aborted."
    exit 1
  fi
fi

# -----------------------------------------------------------------------------
# Step 3: Delete old RAG retry topology
# -----------------------------------------------------------------------------
echo ""
echo "Step 2: Deleting old RAG retry queues..."

for queue in docbase.rag.retry.30s.queue docbase.rag.retry.5m.queue docbase.rag.retry.30m.queue docbase.rag.ingest.queue; do
  echo "  Deleting queue: $queue"
  curl -fsS -u "${username}:${password}" -X DELETE \
    "${base_url}/api/queues/${VHOST_ENCODED}/${queue}" \
    >/dev/null 2>&1 || echo "  [SKIP] Queue '$queue' not found"
done

echo ""
echo "Step 3: Deleting old RAG retry DLX..."
curl -fsS -u "${username}:${password}" -X DELETE \
  "${base_url}/api/exchanges/${VHOST_ENCODED}/docbase.rag.retry.dlx" \
  >/dev/null 2>&1 || echo "  [SKIP] Exchange 'docbase.rag.retry.dlx' not found"

# -----------------------------------------------------------------------------
# Step 4: Verify deletion
# -----------------------------------------------------------------------------
echo ""
echo "Step 4: Verifying deletion..."
sleep 2

has_failure=false
for queue in docbase.rag.retry.30s.queue docbase.rag.retry.5m.queue docbase.rag.retry.30m.queue docbase.rag.ingest.queue; do
  if curl -fsS -u "${username}:${password}" -o /dev/null \
    "${base_url}/api/queues/${VHOST_ENCODED}/${queue}" 2>/dev/null; then
    echo "  [FAIL] Queue '$queue' still exists!"
    has_failure=true
  else
    echo "  [OK] Queue '$queue' deleted"
  fi
done

# CRITICAL: Return non-zero exit code if any queue still exists
if [ "$has_failure" = true ]; then
  echo ""
  echo "=== Migration FAILED — some queues still exist ==="
  exit 1
fi

echo ""
echo "=== Migration complete ==="
echo "Next steps:"
echo "  1. Run init-topology.sh to create new RAG retry topology"
echo "  2. Start RAG service"
echo ""
echo "Rollback: If issues occur, restore from backup or re-register documents."
