package com.docbase.ingest.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.contracts.IngestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes ingest status feedback events to RabbitMQ via outbox pattern.
 *
 * Design:
 * - Claims events atomically (PENDING -> PUBLISHING) to prevent duplicate publishing
 * - Uses RabbitMQ publisher confirms to ensure message delivery
 * - Only marks PUBLISHED after receiving broker confirm callback
 * - Records failures with retry count and next retry time
 * - Events exceeding max retries are moved to DEAD status
 * - Recovers stale PUBLISHING events after timeout (crash recovery)
 */
@Component
public class IngestEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestEventPublisher.class);

    public static final String EXCHANGE_NAME = "docbase.ingest.events";

    private final IngestOutboxMapper outboxMapper;
    final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.application.name:ingest-service}")
    private String instanceId;

    @Value("${docbase.ingest.outbox.poll-batch-size:10}")
    private int pollBatchSize;

    @Value("${docbase.ingest.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${docbase.ingest.outbox.retry-delays:PT30S,PT5M,PT30M}")
    private String[] retryDelays;

    // Track pending confirms (in-memory, used only for callback routing)
    private final ConcurrentHashMap<String, IngestOutboxEntity> pendingConfirms = new ConcurrentHashMap<>();

    public IngestEventPublisher(IngestOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;

        // Enable publisher confirms
        this.rabbitTemplate.setConfirmCallback(this::handleConfirm);
    }

    private void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) return;
        String eventId = correlationData.getId();
        IngestOutboxEntity event = pendingConfirms.remove(eventId);
        if (event == null) return;

        // Check for returned (unroutable) message first
        // Even with ACK, if the message was returned (no matching queue), it's not actually delivered
        if (correlationData.getReturned() != null) {
            String replyText = correlationData.getReturned().getReplyText();
            log.warn("Ingest event returned (unroutable): {} reply={}", eventId, replyText);
            handleFailure(event, "Message returned (unroutable): " + replyText);
            return;
        }

        if (ack) {
            markPublished(eventId);
            log.info("Ingest event confirmed by broker: {} type={}", eventId, event.getEventType());
        } else {
            log.warn("Ingest event rejected by broker: {} cause={}", eventId, cause);
            handleFailure(event, "Broker rejected: " + cause);
        }
    }


    @Scheduled(fixedDelayString = "${docbase.ingest.outbox.poll-interval-ms:5000}")
    public void pollAndPublish() {
        // First, recover stale PUBLISHING events (crash recovery)
        int recovered = recoverStalePublishingEvents(300); // 5 minute timeout
        if (recovered > 0) {
            log.info("Recovered {} stale PUBLISHING events", recovered);
        }

        List<IngestOutboxEntity> events = outboxMapper.selectList(
                new QueryWrapper<IngestOutboxEntity>()
                        .in("status", "PENDING", "FAILED")
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", LocalDateTime.now()))
                        .orderByAsc("created_at")
                        .last("LIMIT " + pollBatchSize)
        );

        if (events.isEmpty()) return;
        log.debug("Found {} ingest events ready for publishing", events.size());

        for (IngestOutboxEntity event : events) {
            publishEvent(event);
        }
    }

    private void publishEvent(IngestOutboxEntity event) {
        // Claim for publishing (PENDING -> PUBLISHING)
        if (!claimForPublishing(event.getEventId())) {
            return;
        }

        try {
            // Build message
            MessageProperties props = new MessageProperties();
            props.setMessageId(event.getEventId());
            props.setContentType("application/json");
            props.setHeader("eventType", event.getEventType());
            props.setHeader("schemaVersion", event.getSchemaVersion());

            Message message = new Message(event.getPayload().getBytes(), props);

            // Track for confirm callback
            pendingConfirms.put(event.getEventId(), event);

            // Send with correlation key matching the topology binding (ingest.document.*)
            String routingKey = deriveRoutingKey(event.getEventType());
            CorrelationData correlationData = new CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, message, correlationData);

            log.debug("Sent ingest event to RabbitMQ: {} type={}", event.getEventId(), event.getEventType());

        } catch (Exception e) {
            pendingConfirms.remove(event.getEventId());
            log.error("Failed to send ingest event: {}", event.getEventId(), e);
            handleFailure(event, e.getMessage());
        }
    }

    private void handleFailure(IngestOutboxEntity event, String error) {
        int retryCount = (event.getRetryCount() != null ? event.getRetryCount() : 0) + 1;
        if (retryCount > maxRetries) {
            markDead(event.getEventId(), "Max retries exceeded: " + error);
        } else {
            Duration delay = getRetryDelay(retryCount);
            recordFailure(event.getEventId(), error, LocalDateTime.now().plus(delay));
        }
    }

    private Duration getRetryDelay(int retryCount) {
        // retryCount is 1-based, array is 0-based
        int index = Math.min(retryCount - 1, retryDelays.length - 1);
        return Duration.parse(retryDelays[index]);
    }

    @Transactional
    protected boolean claimForPublishing(String eventId) {
        IngestOutboxEntity entity = outboxMapper.selectById(eventId);
        if (entity == null) return false;
        if ("PUBLISHING".equals(entity.getStatus()) || "PUBLISHED".equals(entity.getStatus())) {
            return false;
        }
        IngestOutboxEntity update = new IngestOutboxEntity();
        update.setEventId(eventId);
        update.setStatus("PUBLISHING");
        update.setPublishedBy(instanceId);
        update.setClaimedAt(LocalDateTime.now());
        int updated = outboxMapper.update(update,
                new QueryWrapper<IngestOutboxEntity>()
                        .eq("event_id", eventId)
                        .in("status", "PENDING", "FAILED")
        );
        return updated > 0;
    }

    @Transactional
    protected void markPublished(String eventId) {
        IngestOutboxEntity update = new IngestOutboxEntity();
        update.setEventId(eventId);
        update.setStatus("PUBLISHED");
        update.setPublishedAt(LocalDateTime.now());
        outboxMapper.update(update,
                new QueryWrapper<IngestOutboxEntity>()
                        .eq("event_id", eventId)
                        .eq("status", "PUBLISHING")
        );
    }

    @Transactional
    protected void markDead(String eventId, String error) {
        IngestOutboxEntity update = new IngestOutboxEntity();
        update.setEventId(eventId);
        update.setStatus("DEAD");
        update.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        outboxMapper.update(update,
                new QueryWrapper<IngestOutboxEntity>().eq("event_id", eventId)
        );
        log.error("Ingest event moved to DEAD: {} reason={}", eventId, error);
    }

    @Transactional
    protected void recordFailure(String eventId, String error, LocalDateTime nextRetryAt) {
        IngestOutboxEntity entity = outboxMapper.selectById(eventId);
        if (entity == null) return;

        int retryCount = (entity.getRetryCount() != null ? entity.getRetryCount() : 0) + 1;
        IngestOutboxEntity update = new IngestOutboxEntity();
        update.setEventId(eventId);
        update.setStatus("FAILED");
        update.setRetryCount(retryCount);
        update.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        update.setNextRetryAt(nextRetryAt);
        outboxMapper.update(update,
                new QueryWrapper<IngestOutboxEntity>()
                        .eq("event_id", eventId)
                        .eq("status", "PUBLISHING")
        );
    }

    /**
     * Recovers stale PUBLISHING events that have been claimed but not published
     * within the timeout period. This handles the case where a instance crashes
     * after claiming but before publishing.
     */
    @Transactional
    public int recoverStalePublishingEvents(int timeoutSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeoutSeconds);
        IngestOutboxEntity update = new IngestOutboxEntity();
        update.setStatus("FAILED");
        update.setLastError("Recovered from stale PUBLISHING state");
        return outboxMapper.update(update,
                new QueryWrapper<IngestOutboxEntity>()
                        .eq("status", "PUBLISHING")
                        .lt("claimed_at", cutoff)
        );
    }

    /**
     * Writes an ingest event to the outbox within the current transaction.
     */
    @Transactional
    public void writeEvent(IngestEvent event) {
        IngestOutboxEntity entity = new IngestOutboxEntity();
        entity.setEventId(event.eventId().toString());
        entity.setAggregateType(event.aggregateType());
        entity.setAggregateId(event.aggregateId());
        entity.setEventType(event.eventType());
        entity.setPayload(serializePayload(event));
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setSchemaVersion(event.schemaVersion());
        entity.setCreatedAt(LocalDateTime.now());
        outboxMapper.insert(entity);
        log.debug("Ingest outbox event written: {} type={}", event.eventId(), event.eventType());
    }

    /**
     * Derives RabbitMQ routing key from event type.
     * Maps to the topology binding pattern: ingest.document.*
     */
    private String deriveRoutingKey(String eventType) {
        if (eventType == null) {
            return "ingest.document.unknown";
        }
        // Map event types to routing keys
        switch (eventType) {
            case "ingest.document.processing":
                return "ingest.document.processing";
            case "ingest.document.dispatched":
                return "ingest.document.dispatched";
            case "ingest.document.succeeded":
                return "ingest.document.succeeded";
            case "ingest.document.failed":
                return "ingest.document.failed";
            case "ingest.document.deleted":
                return "ingest.document.deleted";
            default:
                return "ingest.document.other";
        }
    }

    private String serializePayload(IngestEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", event.eventId().toString());
        node.put("eventType", event.eventType());
        node.put("aggregateType", event.aggregateType());
        node.put("aggregateId", event.aggregateId());
        node.put("knowledgeBaseId", event.knowledgeBaseId());
        node.put("documentId", event.documentId());
        node.put("ingestStatus", event.ingestStatus());
        node.put("operatorId", event.operatorId());
        node.put("schemaVersion", event.schemaVersion());
        node.put("occurredAt", event.occurredAt().toString());
        return node.toString();
    }
}
