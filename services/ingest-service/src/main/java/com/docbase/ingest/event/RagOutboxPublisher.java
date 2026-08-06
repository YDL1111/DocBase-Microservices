package com.docbase.ingest.event;

import com.docbase.ingest.task.IngestTaskService;
import com.docbase.ingest.task.domain.RagOutboxEntity;
import com.docbase.ingest.task.mapper.RagOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes RAG events from the outbox table to RabbitMQ.
 *
 * Features:
 * - Atomic claim to prevent multi-instance duplicate publishing
 * - Waits for RabbitMQ publisher confirm before marking PUBLISHED
 * - Checks mandatory return to detect unroutable messages
 * - Updates task to DISPATCHED in same transaction as Outbox PUBLISHED
 * - Stale PUBLISHING recovery for crash recovery
 * - Limited retry with exponential backoff
 * - DEAD state after max retries
 */
@Component
public class RagOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RagOutboxPublisher.class);

    public static final String EXCHANGE_NAME = "docbase.document.exchange";
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final RagOutboxMapper ragOutboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final IngestTaskService taskService;

    @Value("${spring.application.name:ingest-service}")
    private String instanceId;

    @Value("${docbase.ingest.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${docbase.ingest.outbox.retry-delays:PT30S,PT5M,PT30M}")
    private String[] retryDelays;

    public RagOutboxPublisher(RagOutboxMapper ragOutboxMapper, RabbitTemplate rabbitTemplate,
                               IngestTaskService taskService) {
        this.ragOutboxMapper = ragOutboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.taskService = taskService;
    }

    /**
     * Poll and publish pending events.
     */
    @Scheduled(fixedDelayString = "${docbase.ingest.outbox.poll-interval-ms:5000}")
    public void pollAndPublish() {
        // First recover stale PUBLISHING events
        recoverStalePublishingEvents();

        List<RagOutboxEntity> events = ragOutboxMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RagOutboxEntity>()
                        .eq("status", "PENDING")
                        .or()
                        .eq("status", "FAILED")
                        .le("next_retry_at", Instant.now())
                        .orderByAsc("created_at")
                        .last("LIMIT 10")
        );

        if (events.isEmpty()) {
            return;
        }

        for (RagOutboxEntity event : events) {
            publishEvent(event);
        }
    }

    private void publishEvent(RagOutboxEntity event) {
        // Atomically claim the event
        int claimed = ragOutboxMapper.claimForPublishing(event.getEventId(), instanceId);
        if (claimed == 0) {
            return; // Already claimed by another instance
        }

        try {
            // Build message
            MessageProperties props = new MessageProperties();
            props.setMessageId(event.getEventId());
            props.setContentType("application/json");
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

            Message message = new Message(event.getPayload().getBytes(), props);

            // Create correlation data for confirm callback
            CorrelationData correlationData = new CorrelationData(event.getEventId());

            // Publish with mandatory=true
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, deriveRoutingKey(event.getEventType()),
                    message, correlationData);

            // Wait for broker confirm
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck()) {
                // Broker NACK'd or timed out
                String reason = confirm == null ? "Confirm timed out" : "Broker NACK";
                log.warn("RAG event not confirmed: {} reason={}", event.getEventId(), reason);
                ragOutboxMapper.markFailed(event.getEventId(), reason, getNextRetryTime(event.getRetryCount() + 1));
                return;
            }

            // Check if message was returned (unroutable)
            if (correlationData.getReturned() != null) {
                log.warn("RAG event returned (unroutable): {} reply={}", event.getEventId(),
                        correlationData.getReturned().getReplyText());
                ragOutboxMapper.markFailed(event.getEventId(),
                        "Message returned: " + correlationData.getReturned().getReplyText(),
                        getNextRetryTime(event.getRetryCount() + 1));
                return;
            }

            // Both ACK and not returned - safe to complete dispatch
            // This updates Outbox to PUBLISHED, task to DISPATCHED, and writes status event
            taskService.completeRagDispatch(event.getEventId(), Long.parseLong(event.getAggregateId()));

            log.info("RAG event confirmed and dispatched: {}", event.getEventId());

        } catch (TimeoutException e) {
            log.error("RAG event confirm timed out: {}", event.getEventId());
            ragOutboxMapper.markFailed(event.getEventId(), "Confirm timeout", getNextRetryTime(event.getRetryCount() + 1));
        } catch (Exception e) {
            log.error("Failed to publish RAG event: {}", event.getEventId(), e);
            ragOutboxMapper.markFailed(event.getEventId(), e.getMessage(), getNextRetryTime(event.getRetryCount() + 1));
        }
    }

    private void recoverStalePublishingEvents() {
        Instant cutoff = Instant.now().minusSeconds(300); // 5 minute timeout
        int recovered = ragOutboxMapper.recoverStalePublishingEvents(
                java.time.LocalDateTime.ofInstant(cutoff, java.time.ZoneId.systemDefault()));
        if (recovered > 0) {
            log.info("Recovered {} stale PUBLISHING RAG events", recovered);
        }
    }

    private String deriveRoutingKey(String eventType) {
        if (eventType == null) {
            return "rag.document.unknown";
        }
        switch (eventType) {
            case "rag.document.ingest.requested":
                return "rag.document.ingest.requested";
            case "rag.document.delete.requested":
                return "rag.document.delete.requested";
            default:
                return "rag.document.other";
        }
    }

    private java.time.LocalDateTime getNextRetryTime(int retryCount) {
        if (retryCount > maxRetries) {
            return null; // Will become DEAD
        }
        try {
            int index = Math.min(retryCount - 1, retryDelays.length - 1);
            java.time.Duration delay = java.time.Duration.parse(retryDelays[index]);
            return java.time.LocalDateTime.now().plus(delay);
        } catch (Exception e) {
            return java.time.LocalDateTime.now().plusMinutes(5);
        }
    }
}
