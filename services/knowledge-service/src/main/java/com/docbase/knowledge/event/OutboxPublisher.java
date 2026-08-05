package com.docbase.knowledge.event;

import com.docbase.contracts.KnowledgeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically polls the outbox table for unpublished events and publishes them to RabbitMQ.
 *
 * Design:
 * - Claims events atomically (PENDING -> PUBLISHING) to prevent duplicate publishing
 * - Uses RabbitMQ publisher confirms to ensure message delivery
 * - Only marks PUBLISHED after receiving broker confirm callback
 * - Records failures with retry count and next retry time
 * - Events exceeding max retries are moved to DEAD status
 * - Recovers stale PUBLISHING events after timeout
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    public static final String EXCHANGE_NAME = "docbase.document.exchange";

    private final OutboxService outboxService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${docbase.outbox.poll-batch-size:10}")
    private int pollBatchSize;

    @Value("${docbase.outbox.max-retries:3}")
    private int maxRetries;

    @Value("${docbase.outbox.retry-delays:PT30S,PT5M,PT30M}")
    private String[] retryDelays;

    // Track pending confirms for events that have been sent but not yet confirmed
    private final ConcurrentHashMap<String, OutboxEntity> pendingConfirms = new ConcurrentHashMap<>();

    public OutboxPublisher(OutboxService outboxService, RabbitTemplate rabbitTemplate) {
        this.outboxService = outboxService;
        this.rabbitTemplate = rabbitTemplate;

        // Enable publisher confirms
        this.rabbitTemplate.setConfirmCallback(this::handleConfirm);
    }

    /**
     * Handle RabbitMQ publisher confirm callbacks.
     * This is called asynchronously by RabbitMQ after message delivery.
     */
    private void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) {
            return;
        }
        String eventId = correlationData.getId();
        OutboxEntity event = pendingConfirms.remove(eventId);

        if (event == null) {
            return;
        }

        if (ack) {
            // Message was successfully delivered to broker
            outboxService.markPublished(eventId);
            log.info("Outbox event confirmed by broker: {} type={}", eventId, event.getEventType());
        } else {
            // Message was rejected by broker
            log.warn("Outbox event rejected by broker: {} cause={}", eventId, cause);
            handlePublishFailure(event, "Broker rejected: " + cause);
        }
    }

    /**
     * Poll and publish pending events.
     * Runs at a fixed interval.
     */
    @Scheduled(fixedDelayString = "${docbase.outbox.poll-interval-ms:5000}")
    public void pollAndPublish() {
        // First, recover any stale PUBLISHING events
        outboxService.recoverStalePublishingEvents(300); // 5 minute timeout

        List<OutboxEntity> events = outboxService.findEventsReadyForPublish(pollBatchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("Found {} events ready for publishing", events.size());

        for (OutboxEntity event : events) {
            publishEvent(event);
        }
    }

    private void publishEvent(OutboxEntity event) {
        // Claim the event for publishing (PENDING -> PUBLISHING)
        if (!outboxService.claimForPublishing(event.getEventId())) {
            return; // Already claimed or published
        }

        try {
            // Build the message
            MessageProperties props = new MessageProperties();
            props.setMessageId(event.getEventId());
            props.setContentType("application/json");
            props.setHeader("eventType", event.getEventType());
            props.setHeader("schemaVersion", event.getSchemaVersion());

            Message message = new Message(event.getPayload().getBytes(), props);

            // Determine routing key from event type
            String routingKey = deriveRoutingKey(event.getEventType());

            // Track this event for confirm callback
            pendingConfirms.put(event.getEventId(), event);

            // Send with correlation data for confirm
            CorrelationData correlationData = new CorrelationData(event.getEventId());

            rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, message, correlationData);

            log.debug("Sent outbox event to RabbitMQ: {} type={}", event.getEventId(), event.getEventType());

        } catch (Exception e) {
            // Failed to send - remove from pending and record failure
            pendingConfirms.remove(event.getEventId());
            log.error("Failed to send outbox event: {}", event.getEventId(), e);
            handlePublishFailure(event, e.getMessage());
        }
    }

    /**
     * Handle a publish failure by recording it and scheduling retry.
     */
    private void handlePublishFailure(OutboxEntity event, String error) {
        int retryCount = (event.getRetryCount() != null ? event.getRetryCount() : 0) + 1;

        if (retryCount > maxRetries) {
            // Max retries exceeded - move to DEAD
            outboxService.markDead(event.getEventId(), "Max retries exceeded: " + error);
            log.error("Outbox event moved to DEAD: {} after {} retries", event.getEventId(), retryCount);
        } else {
            // Schedule retry
            Duration delay = retryCount <= retryDelays.length ?
                    Duration.parse(retryDelays[retryCount - 1]) :
                    Duration.parse(retryDelays[retryDelays.length - 1]);
            Instant nextRetryAt = Instant.now().plus(delay);

            outboxService.markFailed(event.getEventId(), error, nextRetryAt);
            log.warn("Outbox event failed, scheduled retry #{} at {}: {}", retryCount, nextRetryAt, event.getEventId());
        }
    }

    /**
     * Derive RabbitMQ routing key from event type.
     */
    private String deriveRoutingKey(String eventType) {
        if (eventType == null) {
            return "unknown";
        }
        // Convert "knowledge.document.registered" to "document.registered"
        int dotIndex = eventType.indexOf('.');
        if (dotIndex > 0 && dotIndex < eventType.length() - 1) {
            return eventType.substring(dotIndex + 1);
        }
        return eventType;
    }
}
