package com.docbase.ingest.consumer;

import com.docbase.contracts.KnowledgeEvent;
import com.docbase.ingest.task.IngestTaskService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumes KnowledgeEvent messages from RabbitMQ.
 *
 * Implements idempotent consumption with manual ACK:
 * 1. Check if eventId already exists in consumed_event table
 * 2. If exists, ACK and skip (already processed)
 * 3. If new, create task and record consumption in a transaction
 * 4. ACK only after successful transaction commit
 * 5. Invalid events are rejected (sent to DLQ via DLX)
 * 6. Transient failures are routed to retry queues with TTL
 *
 * Retry strategy:
 * - First failure: route to 30s retry queue
 * - Second failure: route to 5m retry queue
 * - Third failure: route to 30m retry queue
 * - Max retries exceeded: route to DLQ
 *
 * Uses database unique constraint on event_id as final guard against duplicates.
 */
@Component
public class KnowledgeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEventConsumer.class);

    private static final int MAX_RETRIES = 3;

    private final KnowledgeEventDeserializer deserializer;
    private final IngestTaskService ingestTaskService;
    private final RetryMessagePublisher retryMessagePublisher;

    public KnowledgeEventConsumer(KnowledgeEventDeserializer deserializer,
                                   IngestTaskService ingestTaskService,
                                   RetryMessagePublisher retryMessagePublisher) {
        this.deserializer = deserializer;
        this.ingestTaskService = ingestTaskService;
        this.retryMessagePublisher = retryMessagePublisher;
    }

    /**
     * Consumes messages from the ingest queue.
     * Uses manual ACK - only ACKs after successful processing.
     */
    @RabbitListener(queues = "docbase.ingest.queue", containerFactory = "rabbitListenerContainerFactory")
    public void consume(Message message, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String payload = new String(message.getBody());
        String messageId = message.getMessageProperties().getMessageId();

        log.info("Received message: messageId={}", messageId);

        try {
            // Deserialize and validate
            KnowledgeEvent event = deserializer.deserialize(payload);

            // Process idempotently (transactional)
            boolean processed = ingestTaskService.processEvent(event);

            // ACK after successful processing or duplicate detection
            channel.basicAck(deliveryTag, false);
            if (processed) {
                log.debug("Processed and ACKed message: {}", messageId);
            } else {
                log.debug("Duplicate event, ACKed and skipped: {}", messageId);
            }

        } catch (KnowledgeEventDeserializer.EventValidationException e) {
            // Invalid event - reject (sent to DLX -> DLQ)
            log.warn("Invalid event, rejecting to DLQ: {}", e.getMessage());
            channel.basicReject(deliveryTag, false); // requeue=false -> DLX -> DLQ

        } catch (Exception e) {
            // Transient failure - route to retry queue
            int retryCount = getRetryCount(message);
            if (retryCount >= MAX_RETRIES) {
                // Max retries exceeded - reject to DLQ
                log.error("Max retries exceeded for event {}, rejecting to DLQ: {}", messageId, e.getMessage());
                channel.basicReject(deliveryTag, false); // requeue=false -> DLX -> DLQ
            } else {
                // Publish to retry queue with incremented count
                log.warn("Transient failure for event {}, retry {}/{}: {}", messageId, retryCount + 1, MAX_RETRIES, e.getMessage());
                publishToRetryQueue(message, channel, deliveryTag, retryCount + 1);
            }
        }
    }

    /**
     * Gets the current retry count from message headers.
     */
    private int getRetryCount(Message message) {
        Object retryHeader = message.getMessageProperties().getHeader("x-retry-count");
        if (retryHeader != null) {
            try {
                return Integer.parseInt(retryHeader.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Publishes a message to the appropriate retry queue based on retry count.
     * Uses RetryMessagePublisher which handles mandatory return and confirm.
     *
     * Only ACKs the original message if the retry message was:
     * 1. Confirmed by broker (ACK)
     * 2. Successfully routed (not returned)
     *
     * Otherwise NACKs the original with requeue=true for retry.
     */
    private void publishToRetryQueue(Message message, Channel channel, long deliveryTag, int retryCount) {
        RetryMessagePublisher.PublishResult result = retryMessagePublisher.publishRetry(message, retryCount);

        try {
            switch (result) {
                case SUCCESS:
                    // Retry message confirmed and routed - safe to ACK original
                    channel.basicAck(deliveryTag, false);
                    log.debug("Retry message confirmed and original ACKed: retryCount={}", retryCount);
                    break;
                case UNROUTABLE:
                    // Retry message was returned (no matching queue) - NACK original
                    log.warn("Retry message unroutable, NACKing original with requeue: retryCount={}", retryCount);
                    safeNack(channel, deliveryTag, true);
                    break;
                case FAILED:
                case TIMEOUT:
                    // Retry publish failed or timed out - NACK original
                    log.warn("Retry publish failed ({}), NACKing original with requeue: retryCount={}", result, retryCount);
                    safeNack(channel, deliveryTag, true);
                    break;
            }
        } catch (IOException e) {
            log.error("Failed to ACK/NACK original message after retry: {}", e.getMessage());
            throw new RuntimeException("Failed to complete retry flow", e);
        }
    }

    /**
     * Safely sends a NACK, handling the case where the channel might be closed.
     * If NACK fails, the exception will propagate and cause the channel to close,
     * triggering consumer recovery.
     */
    private void safeNack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("Failed to NACK message, channel may close: {}", e.getMessage());
            throw new RuntimeException("NACK failed", e);
        }
    }
}
