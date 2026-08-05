-- =============================================================================
-- V5: Add claimed_at column for stale claim recovery
--
-- Problem: recoverStalePublishingEvents references updated_at which doesn't exist
-- in the event_outbox table. We need a dedicated claimed_at column to track
-- when an event was claimed for publishing.
--
-- Solution: Add claimed_at column that is set when an event transitions to
-- PUBLISHING status. This allows recovery of stale claims after a timeout.
-- =============================================================================

ALTER TABLE event_outbox
    ADD COLUMN claimed_at DATETIME NULL DEFAULT NULL COMMENT '认领时间，用于超时恢复';

-- Index for efficient stale claim recovery queries
CREATE INDEX idx_event_outbox_claimed_at ON event_outbox (claimed_at);
