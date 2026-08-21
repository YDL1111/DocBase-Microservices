package com.docbase.ingest.consumer;

import com.docbase.contracts.KnowledgeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEventDeserializerTest {

    private final KnowledgeEventDeserializer deserializer =
            new KnowledgeEventDeserializer(new ObjectMapper());

    @Test
    void versionOneEventRemainsCompatibleWithoutRetrievalMetadata() throws Exception {
        KnowledgeEvent event = deserializer.deserialize("""
                {
                  "eventId":"11111111-1111-1111-1111-111111111111",
                  "eventType":"knowledge.document.created",
                  "aggregateType":"document",
                  "aggregateId":"9",
                  "knowledgeBaseId":5,
                  "documentId":8,
                  "versionId":13,
                  "objectKey":"knowledge/5/doc.txt",
                  "fileName":"doc.txt",
                  "contentType":"text/plain",
                  "schemaVersion":1,
                  "occurredAt":"2026-08-20T01:02:03Z"
                }
                """);

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.documentTitle()).isNull();
        assertThat(event.folderId()).isNull();
        assertThat(event.visibility()).isNull();
        assertThat(event.documentCreatedAt()).isNull();
        assertThat(event.documentUpdatedAt()).isNull();
    }

    @Test
    void versionTwoEventKeepsRetrievalMetadata() throws Exception {
        KnowledgeEvent event = deserializer.deserialize("""
                {
                  "eventId":"22222222-2222-2222-2222-222222222222",
                  "eventType":"knowledge.document.created",
                  "aggregateType":"document",
                  "aggregateId":"9",
                  "knowledgeBaseId":5,
                  "documentId":8,
                  "versionId":13,
                  "objectKey":"knowledge/5/doc.txt",
                  "fileName":"doc.txt",
                  "contentType":"text/plain",
                  "documentTitle":"安全生产手册",
                  "folderId":12,
                  "visibility":1,
                  "documentCreatedAt":"2026-08-20T01:02:03Z",
                  "documentUpdatedAt":"2026-08-21T04:05:06Z",
                  "schemaVersion":2,
                  "occurredAt":"2026-08-21T04:05:07Z"
                }
                """);

        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(event.documentTitle()).isEqualTo("安全生产手册");
        assertThat(event.folderId()).isEqualTo(12L);
        assertThat(event.visibility()).isEqualTo(1);
        assertThat(event.documentCreatedAt()).isEqualTo(Instant.parse("2026-08-20T01:02:03Z"));
        assertThat(event.documentUpdatedAt()).isEqualTo(Instant.parse("2026-08-21T04:05:06Z"));
    }
}
