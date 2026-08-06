package com.docbase.ingest.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Consumes RAG result events from rag-service.
 *
 * Features:
 * - Manual ACK - only ACKs after successful transaction commit
 * - Idempotent - uses RagResultEvent table for deduplication
 * - Invalid events are rejected (sent to DLQ)
 * - Transient failures go to limited retry queue with TTL via RabbitTemplate
 * - Max retries exceeded -> DLQ
 */
@Component
public class RagResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(RagResultConsumer.class);

    public static final String RAG_RESULT_QUEUE = "docbase.rag.result.queue";
    public static final String RAG_RESULT_DLX = "docbase.rag.result.dlx";
    public static final String RAG_RESULT_EXCHANGE = "docbase.rag.result.exchange";
    public static final String RAG_RESULT_ROUTING_PREFIX = "rag.result";

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_CONFIRM_TIMEOUT_MS = 5000;

    private final RagResultService resultService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    public RagResultConsumer(RagResultService resultService, ObjectMapper objectMapper,
                              RabbitTemplate rabbitTemplate) {
        this.resultService = resultService;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Consumes RAG result events from the dedicated result queue.
     * Uses manual ACK - only ACKs after successful processing.
     */
    @RabbitListener(queues = RAG_RESULT_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void consume(Message message, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String messageId = message.getMessageProperties().getMessageId();

        try {
            String payload = new String(message.getBody());
            JsonNode json = objectMapper.readTree(payload);

            String eventType = json.get("eventType").asText();
            String aggregateId = json.get("aggregateId").asText();
            String resultEventId = json.has("eventId") ? json.get("eventId").asText() : messageId;
            // Map RAG result event types to Ingest event types
            String ingestEventType = mapToIngestEventType(eventType);

            log.info("Received RAG result: messageId={}, type={}, aggregateId={}", messageId, eventType, aggregateId);

            // Find the ingest task by ID (aggregateId is the task ID)
            Long taskId = Long.parseLong(aggregateId);

            // Process idempotently in a transaction (via separate service)
            resultService.processResultEvent(resultEventId, ingestEventType, taskId, json);

            // ACK after successful processing
            channel.basicAck(deliveryTag, false);
            log.debug("ACKed message: {}", messageId);

        } catch (IllegalArgumentException e) {
            // Invalid event - reject to DLQ (don't requeue)
            log.warn("Invalid RAG result event, rejecting to DLQ: {}", e.getMessage());
            channel.basicReject(deliveryTag, false); // requeue=false -> DLQ

        } catch (Exception e) {
            log.error("Failed to process RAG result: {}", e.getMessage(), e);

            // Check retry count from headers
            int retryCount = getRetryCount(message);

            if (retryCount >= MAX_RETRIES) {
                // Max retries exceeded - reject to DLQ
                log.warn("Max retries exceeded for RAG result, rejecting to DLQ: {}", messageId);
                channel.basicReject(deliveryTag, false); // requeue=false -> DLQ
            } else {
                // Publish to retry queue with incremented count, wait for confirm, then ACK original
                log.warn("Transient failure for RAG result, retry {}/{}: {}", retryCount + 1, MAX_RETRIES, messageId);
                publishToRetryQueueAndAck(message, channel, deliveryTag, retryCount + 1);
            }
        }
    }

    /**
     * Gets the current retry count from message headers.
     */
    private int getRetryCount(Message message) {
        Object retryHeader = message.getMessageProperties().getHeader("x-retry-count");
        if (retryHeader instanceof Integer) {
            return (Integer) retryHeader;
        }
        return 0;
    }

    /**
     * Publishes a message to the appropriate retry queue using RabbitTemplate.
     * Waits for broker confirm before ACKing the original message.
     *
     * This ensures:
     * 1. Broker ACK (message received)
     * 2. No returned message (message was routed to at least one queue)
     *
     * Only after both conditions are met do we ACK the original message.
     */
    private void publishToRetryQueueAndAck(Message message, Channel channel, long deliveryTag, int retryCount) throws IOException {
        String routingKey = getRetryRoutingKey(retryCount);
        String messageId = message.getMessageProperties().getMessageId();

        try {
            // Create correlation data for confirm callback
            CorrelationData correlationData = new CorrelationData(messageId + "-retry-" + retryCount);

            // Build message with retry header
            org.springframework.amqp.core.MessageProperties props = new org.springframework.amqp.core.MessageProperties();
            props.setMessageId(messageId);
            props.setContentType("application/json");
            props.setHeader("x-retry-count", retryCount);

            org.springframework.amqp.core.Message retryMessage =
                    new org.springframework.amqp.core.Message(message.getBody(), props);

            // Publish to DLX with retry routing key (retry queues bind to DLX)
            rabbitTemplate.convertAndSend(RAG_RESULT_DLX, routingKey, retryMessage, correlationData);

            // Wait for broker confirm
            CorrelationData.Confirm confirm = correlationData.getFuture().get(RETRY_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck()) {
                // Broker NACK'd or timed out - don't ACK original, let it be redelivered
                String reason = confirm == null ? "Confirm timed out" : "Broker NACK";
                log.warn("Retry publish not confirmed: {} reason={}", messageId, reason);
                channel.basicNack(deliveryTag, false, true); // requeue=true for retry
                return;
            }

            // Check if message was returned (unroutable)
            if (correlationData.getReturned() != null) {
                log.warn("Retry publish returned (unroutable): {} reply={}", messageId,
                        correlationData.getReturned().getReplyText());
                channel.basicNack(deliveryTag, false, true); // requeue=true for retry
                return;
            }

            // Both ACK and not returned - safe to ACK original
            channel.basicAck(deliveryTag, false);
            log.debug("Retry publish confirmed and original ACKed: {}", messageId);

        } catch (TimeoutException e) {
            log.error("Retry publish confirm timed out: {}", messageId);
            channel.basicNack(deliveryTag, false, true); // requeue=true for retry
        } catch (Exception e) {
            log.error("Failed to publish retry message: {}", messageId, e);
            channel.basicNack(deliveryTag, false, true); // requeue=true for retry
        }
    }

    /**
     * Maps RAG result event types to Ingest event types.
     */
    private String mapToIngestEventType(String ragEventType) {
        switch (ragEventType) {
            case "rag.document.ingest.completed":
                return "ingest.document.succeeded";
            case "rag.document.ingest.failed":
                return "ingest.document.failed";
            case "rag.document.delete.completed":
                return "ingest.document.deleted";
            default:
                return ragEventType; // Pass through unknown types
        }
    }

    /**
     * Gets the routing key for retry based on retry count.
     */
    private String getRetryRoutingKey(int retryCount) {
        switch (retryCount) {
            case 1:
                return "retry.1"; // 30s queue
            case 2:
                return "retry.2"; // 5m queue
            default:
                return "failed"; // DLQ
        }
    }
}
