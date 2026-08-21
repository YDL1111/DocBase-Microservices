package com.docbase.chat.session.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ai_chat_session_knowledge_base")
public class ChatSessionKnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long knowledgeBaseId;

    public ChatSessionKnowledgeBase() {}

    public ChatSessionKnowledgeBase(Long sessionId, Long knowledgeBaseId) {
        this.sessionId = sessionId;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
}
