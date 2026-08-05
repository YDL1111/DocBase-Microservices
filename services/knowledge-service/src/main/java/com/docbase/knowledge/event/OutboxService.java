package com.docbase.knowledge.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.contracts.KnowledgeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for writing events to the Outbox table within the same transaction
 * as business operations. This ensures atomicity: either both the business
 * change and the outbox event are committed, or neither is.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;
    private final String instanceId;

    public OutboxService(OutboxEventMapper outboxEventMapper,
                         ObjectMapper objectMapper,
                         @Value("${spring.application.name:knowledge-service}") String instanceId) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
    }

    /**
     * Writes an event to the outbox within the current transaction.
     */
    @Transactional
    public void writeEvent(KnowledgeEvent event) {
        OutboxEntity entity = new OutboxEntity();
        entity.setEventId(event.eventId().toString());
        entity.setAggregateType(event.aggregateType());
        entity.setAggregateId(event.aggregateId());
        entity.setEventType(event.eventType());
        entity.setPayload(serializePayload(event));
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setSchemaVersion(event.schemaVersion());
        entity.setCreatedAt(Instant.now());
        outboxEventMapper.insert(entity);
        log.debug("Outbox event written: {} type={}", event.eventId(), event.eventType());
    }

    /**
     * Finds events ready for publishing (PENDING or FAILED with next_retry_at <= now).
     * Uses optimistic locking to prevent duplicate publishing across instances.
     */
    public List<OutboxEntity> findEventsReadyForPublish(int limit) {
        // Use SQL directly for correct OR logic
        return outboxEventMapper.selectList(
                new QueryWrapper<OutboxEntity>()
                        .in("status", "PENDING", "FAILED")
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", Instant.now()))
                        .orderByAsc("created_at")
                        .last("LIMIT " + limit)
        );
    }

    /**
     * Atomically claims an event for publishing by this instance.
     * Sets status to PUBLISHING to prevent other instances from picking it up.
     * Returns true if successfully claimed, false if already claimed by another instance.
     */
    @Transactional
    public boolean claimForPublishing(String eventId) {
        // Use optimistic update: only claim if status is PENDING or FAILED
        OutboxEntity entity = outboxEventMapper.selectById(eventId);
        if (entity == null) {
            return false;
        }
        // Skip if already being published or published
        if ("PUBLISHING".equals(entity.getStatus()) || "PUBLISHED".equals(entity.getStatus())) {
            return false;
        }

        // Set status to PUBLISHING with instance identifier and claim time
        OutboxEntity update = new OutboxEntity();
        update.setEventId(eventId);
        update.setStatus("PUBLISHING");
        update.setPublishedBy(instanceId);
        update.setClaimedAt(Instant.now());

        // Conditional update to prevent race conditions
        int updated = outboxEventMapper.update(update,
                new QueryWrapper<OutboxEntity>()
                        .eq("event_id", eventId)
                        .in("status", "PENDING", "FAILED")
        );
        return updated > 0;
    }

    /**
     * Marks an event as published after successful RabbitMQ confirm.
     * This is called only after receiving broker confirmation.
     */
    @Transactional
    public void markPublished(String eventId) {
        OutboxEntity entity = new OutboxEntity();
        entity.setEventId(eventId);
        entity.setStatus("PUBLISHED");
        entity.setPublishedAt(Instant.now());
        outboxEventMapper.update(entity,
                new QueryWrapper<OutboxEntity>()
                        .eq("event_id", eventId)
                        .eq("status", "PUBLISHING") // Only update if still in PUBLISHING
        );
    }

    /**
     * Marks an event as failed and increments retry count.
     * Called when RabbitMQ send fails.
     */
    @Transactional
    public void markFailed(String eventId, String error, Instant nextRetryAt) {
        OutboxEntity entity = outboxEventMapper.selectById(eventId);
        if (entity == null) {
            return;
        }
        OutboxEntity update = new OutboxEntity();
        update.setEventId(eventId);
        update.setStatus("FAILED");
        update.setRetryCount(entity.getRetryCount() + 1);
        update.setLastError(error != null && error.length() > 512 ? error.substring(0, 512) : error);
        update.setNextRetryAt(nextRetryAt);
        outboxEventMapper.update(update,
                new QueryWrapper<OutboxEntity>()
                        .eq("event_id", eventId)
                        .eq("status", "PUBLISHING") // Only update if in PUBLISHING
        );
    }

    /**
     * Recovers stale PUBLISHING events that have been claimed but not published
     * within the timeout period. This handles the case where a instance crashes
     * after claiming but before publishing.
     */
    @Transactional
    public int recoverStalePublishingEvents(int timeoutSeconds) {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        OutboxEntity update = new OutboxEntity();
        update.setStatus("FAILED");
        update.setLastError("Recovered from stale PUBLISHING state");
        return outboxEventMapper.update(update,
                new QueryWrapper<OutboxEntity>()
                        .eq("status", "PUBLISHING")
                        .lt("claimed_at", cutoff)
        );
    }

    /**
     * Records a publish failure and schedules retry.
     */
    @Transactional
    public void recordPublishFailure(String eventId, String error, Instant nextRetryAt) {
        OutboxEntity entity = outboxEventMapper.selectById(eventId);
        if (entity == null) {
            return;
        }
        int retryCount = (entity.getRetryCount() != null ? entity.getRetryCount() : 0) + 1;
        entity.setRetryCount(retryCount);
        entity.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        entity.setNextRetryAt(nextRetryAt);
        entity.setStatus("FAILED");
        outboxEventMapper.updateById(entity);
    }

    /**
     * Marks an event as DEAD (permanently failed).
     */
    @Transactional
    public void markDead(String eventId, String error) {
        OutboxEntity entity = outboxEventMapper.selectById(eventId);
        if (entity == null) {
            return;
        }
        OutboxEntity update = new OutboxEntity();
        update.setEventId(eventId);
        update.setStatus("DEAD");
        update.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        outboxEventMapper.update(update,
                new QueryWrapper<OutboxEntity>().eq("event_id", eventId)
        );
        log.error("Outbox event moved to DEAD: {} reason={}", eventId, error);
    }

    private String serializePayload(KnowledgeEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", event.eventId().toString());
        node.put("eventType", event.eventType());
        node.put("aggregateType", event.aggregateType());
        node.put("aggregateId", event.aggregateId());
        node.put("knowledgeBaseId", event.knowledgeBaseId());
        node.put("documentId", event.documentId());
        node.put("objectKey", event.objectKey());
        node.put("operatorId", event.operatorId());
        node.put("schemaVersion", event.schemaVersion());
        node.put("occurredAt", event.occurredAt().toString());
        return node.toString();
    }
}
