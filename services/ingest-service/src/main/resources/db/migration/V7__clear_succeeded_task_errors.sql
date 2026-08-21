-- Successful tasks must not retain failure metadata from an earlier retry.
-- This repairs historical rows affected by MyBatis-Plus skipping null fields
-- in updateById; future writes clear both columns explicitly in the service.
UPDATE ingest_task
SET last_error = NULL,
    next_retry_at = NULL
WHERE status = 'SUCCEEDED'
  AND (last_error IS NOT NULL OR next_retry_at IS NOT NULL);
