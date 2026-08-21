package com.docbase.ingest.task;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RAG event payload DTO.
 * Uses camelCase to match Python Pydantic contract.
 */
public class RagEventPayload {

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("aggregateType")
    private String aggregateType;

    @JsonProperty("aggregateId")
    private String aggregateId;

    @JsonProperty("knowledgeBaseId")
    private Long knowledgeBaseId;

    @JsonProperty("documentId")
    private Long documentId;

    @JsonProperty("versionId")
    private Long versionId;

    @JsonProperty("objectKey")
    private String objectKey;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("contentType")
    private String contentType;

    @JsonProperty("documentTitle")
    private String documentTitle;

    @JsonProperty("folderId")
    private Long folderId;

    @JsonProperty("visibility")
    private Integer visibility;

    @JsonProperty("documentCreatedAt")
    private String documentCreatedAt;

    @JsonProperty("documentUpdatedAt")
    private String documentUpdatedAt;

    @JsonProperty("operatorId")
    private Long operatorId;

    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("occurredAt")
    private String occurredAt;

    @JsonProperty("traceId")
    private String traceId;

    // Getters and setters
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getDocumentTitle() { return documentTitle; }
    public void setDocumentTitle(String documentTitle) { this.documentTitle = documentTitle; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
    public Integer getVisibility() { return visibility; }
    public void setVisibility(Integer visibility) { this.visibility = visibility; }
    public String getDocumentCreatedAt() { return documentCreatedAt; }
    public void setDocumentCreatedAt(String documentCreatedAt) { this.documentCreatedAt = documentCreatedAt; }
    public String getDocumentUpdatedAt() { return documentUpdatedAt; }
    public void setDocumentUpdatedAt(String documentUpdatedAt) { this.documentUpdatedAt = documentUpdatedAt; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getOccurredAt() { return occurredAt; }
    public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
