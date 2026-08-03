package com.docbase.contracts;

import java.time.Instant;
import java.util.UUID;

public record DocumentEvent(
        UUID eventId,
        String eventType,
        Long documentId,
        Long versionId,
        Instant occurredAt
) {
}
