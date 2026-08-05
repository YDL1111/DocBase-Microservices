package com.docbase.ingest.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes retry messages to RabbitMQ with full reliability guarantees.
 *
 * Uses a configured RabbitTemplate with:
 * - publisher-confirm-type: correlated
 * - publisher-returns: true
 * - template.mandatory: true
 *
 * Only confirms successful delivery after:
 * 1. Broker ACK (message received by exchange)
 * 2. No returned message (message was routed to at least one queue)
 *
 * This prevents silent message loss when retry queues or bindings don't exist.
 */
@Component
public class RetryMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RetryMessagePublisher.class);

    private static final String RETRY_EXCHANGE = "docbase.ingest.dlx";
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final RabbitTemplate rabbitTemplate;

    @Value("${spring.application.name:ingest-service}")
    private String instanceId;

    public RetryMessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Result of a retry publish operation.
     */
    public enum PublishResult {
        SUCCESS,        // Message confirmed and routed
        FAILED,         // Broker NACK or other failure
        UNROUTABLE,     // Message returned (no matching queue)
        TIMEOUT         // Confirm timed out
    }

    /**
     * Publishes a message to the retry queue with full confirmation.
     *
     * @param message the original message to retry
     * @param retryCount the current retry count (determines which queue)
     * @return the result of the publish operation
     */
    public PublishResult publishRetry(Message message, int retryCount) {
        // Build routing key based on retry count
        String routingKey = deriveRoutingKey(retryCount);

        // Build Spring AMQP MessageProperties with retry header
        MessageProperties props = new MessageProperties();
        props.setMessageId(message.getMessageProperties().getMessageId());
        props.setContentType("application/json");
        props.setHeader("x-retry-count", retryCount);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        // Create the Spring AMQP Message
        org.springframework.amqp.core.Message amqpMessage =
                new org.springframework.amqp.core.Message(message.getBody(), props);

        // Create unique correlation data for this publish
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);

        try {
            // Send with mandatory=true (configured in RabbitTemplate)
            rabbitTemplate.convertAndSend(RETRY_EXCHANGE, routingKey, amqpMessage, correlationData);

            // Wait for async confirm callback
            // The RabbitTemplate's ConfirmCallback will populate the CorrelationData
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (confirm == null) {
                log.warn("Retry publish timed out: retryCount={}, routingKey={}", retryCount, routingKey);
                return PublishResult.TIMEOUT;
            }

            if (!confirm.isAck()) {
                log.warn("Retry publish NACK'd by broker: retryCount={}, routingKey={}",
                        retryCount, routingKey);
                return PublishResult.FAILED;
            }

            // Check if message was returned (unroutable)
            if (correlationData.getReturned() != null) {
                log.warn("Retry publish returned (unroutable): retryCount={}, routingKey={}, reply={}",
                        retryCount, routingKey, correlationData.getReturned().getReplyText());
                return PublishResult.UNROUTABLE;
            }

            // Both ACK and not returned - message is safely queued
            log.debug("Retry publish confirmed and routed: retryCount={}, routingKey={}", retryCount, routingKey);
            return PublishResult.SUCCESS;

        } catch (TimeoutException e) {
            log.warn("Retry publish confirm timed out: retryCount={}, routingKey={}", retryCount, routingKey);
            return PublishResult.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retry publish interrupted: retryCount={}, routingKey={}", retryCount, routingKey);
            return PublishResult.FAILED;
        } catch (Exception e) {
            log.error("Retry publish failed: retryCount={}, routingKey={}, error={}", retryCount, routingKey, e.getMessage());
            return PublishResult.FAILED;
        }
    }

    /**
     * Derives the routing key based on retry count.
     */
    private String deriveRoutingKey(int retryCount) {
        switch (retryCount) {
            case 1:
                return "retry.1"; // 30s queue
            case 2:
                return "retry.2"; // 5m queue
            case 3:
                return "retry.3"; // 30m queue
            default:
                return "failed"; // DLQ
        }
    }
}
