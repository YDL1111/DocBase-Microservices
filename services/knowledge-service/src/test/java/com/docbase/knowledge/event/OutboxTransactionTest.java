package com.docbase.knowledge.event;

import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.contracts.KnowledgeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class OutboxTransactionTest {

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    @Autowired
    OutboxService outboxService;

    @Test
    void createKnowledgeBase_WritesOutboxEvent() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Outbox Test Base");

        Long baseId = knowledgeBaseService.create(base, 1L);

        // Verify outbox event was written
        List<OutboxEntity> pendingEvents = outboxService.findPendingEvents(10);
        assertThat(pendingEvents).isNotEmpty();

        // Find the event for this base
        OutboxEntity event = pendingEvents.stream()
                .filter(e -> e.getAggregateId().equals(baseId.toString()))
                .findFirst()
                .orElse(null);

        assertThat(event).isNotNull();
        assertThat(event.getEventType()).isEqualTo(KnowledgeEvent.BASE_CREATED);
        assertThat(event.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void deleteKnowledgeBase_WritesOutboxEvent() {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Delete Outbox Test");
        Long baseId = knowledgeBaseService.create(base, 1L);

        // Clear pending events from create
        List<OutboxEntity> events = outboxService.findPendingEvents(100);
        for (OutboxEntity e : events) {
            outboxService.markPublished(e.getEventId());
        }

        // Delete the base
        knowledgeBaseService.delete(baseId, 1L, false);

        // Verify delete event was written
        List<OutboxEntity> pendingEvents = outboxService.findPendingEvents(10);
        OutboxEntity deleteEvent = pendingEvents.stream()
                .filter(e -> e.getEventType().equals(KnowledgeEvent.BASE_DELETED))
                .findFirst()
                .orElse(null);

        assertThat(deleteEvent).isNotNull();
        assertThat(deleteEvent.getAggregateId()).isEqualTo(baseId.toString());
    }

    @Test
    void markPublished_UpdatesStatus() {
        // Write an event
        KnowledgeEvent event = new KnowledgeEvent(
                UUID.randomUUID(),
                "test.event",
                "test",
                "123",
                1L,
                null,
                null,
                1L,
                1,
                java.time.Instant.now()
        );
        outboxService.writeEvent(event);

        // Mark as published
        outboxService.markPublished(event.eventId().toString());

        // Verify it's no longer pending
        List<OutboxEntity> pendingEvents = outboxService.findPendingEvents(10);
        boolean stillPending = pendingEvents.stream()
                .anyMatch(e -> e.getEventId().equals(event.eventId().toString()));
        assertThat(stillPending).isFalse();
    }
}
