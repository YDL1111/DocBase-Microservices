package com.docbase.chat.session.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@TableName("ai_chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long knowledgeBaseId;

    /** Effective bindings. Stored in ai_chat_session_knowledge_base, not this table. */
    @TableField(exist = false)
    private List<Long> knowledgeBaseIds = new ArrayList<>();

    private String title;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public List<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds == null ? new ArrayList<>() : new ArrayList<>(knowledgeBaseIds);
    }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
