package com.docbase.ingest.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ingest_task")
public class IngestTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private Long knowledgeBaseId;

    private Long documentId;

    private Long versionId;

    private String objectKey;

    private String fileName;

    private String contentType;

    private String documentTitle;

    private Long folderId;

    private Integer visibility;

    private LocalDateTime documentCreatedAt;

    private LocalDateTime documentUpdatedAt;

    private String taskType;

    private String status;

    private Integer attemptCount;

    private String lastError;

    private LocalDateTime nextRetryAt;

    private String pythonKbId;

    private String pythonDocId;

    private Integer chunkCount;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
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
    public LocalDateTime getDocumentCreatedAt() { return documentCreatedAt; }
    public void setDocumentCreatedAt(LocalDateTime documentCreatedAt) { this.documentCreatedAt = documentCreatedAt; }
    public LocalDateTime getDocumentUpdatedAt() { return documentUpdatedAt; }
    public void setDocumentUpdatedAt(LocalDateTime documentUpdatedAt) { this.documentUpdatedAt = documentUpdatedAt; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getPythonKbId() { return pythonKbId; }
    public void setPythonKbId(String pythonKbId) { this.pythonKbId = pythonKbId; }
    public String getPythonDocId() { return pythonDocId; }
    public void setPythonDocId(String pythonDocId) { this.pythonDocId = pythonDocId; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
