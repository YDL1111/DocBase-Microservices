package com.docbase.knowledge.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.contracts.KnowledgeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OutboxService(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes an event to the outbox within the current transaction.
     * The event will be published to RabbitMQ by a separate publisher process.
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
        entity.setCreatedAt(Instant.now());
        outboxEventMapper.insert(entity);
        log.debug("Outbox event written: {} type={}", event.eventId(), event.eventType());
    }

    /**
     * Finds pending events for publishing.
     */
    public List<OutboxEntity> findPendingEvents(int limit) {
        return outboxEventMapper.selectList(
                new QueryWrapper<OutboxEntity>()
                        .eq("status", "PENDING")
                        .orderByAsc("created_at")
                        .last("LIMIT " + limit)
        );
    }

    /**
     * Marks an event as published.
     */
    @Transactional
    public void markPublished(String eventId) {
        OutboxEntity entity = new OutboxEntity();
        entity.setEventId(eventId);
        entity.setStatus("PUBLISHED");
        entity.setPublishedAt(Instant.now());
        outboxEventMapper.update(entity,
                new QueryWrapper<OutboxEntity>().eq("event_id", eventId));
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
