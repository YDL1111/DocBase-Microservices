-- A session may use zero, one, or several knowledge bases.
-- Keep ai_chat_session.knowledge_base_id as a compatibility mirror of the first binding.
CREATE TABLE ai_chat_session_knowledge_base (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    session_id        BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_session_kb (session_id, knowledge_base_id),
    KEY idx_chat_session_kb_base (knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI会话知识库绑定';

INSERT INTO ai_chat_session_knowledge_base (session_id, knowledge_base_id)
SELECT id, knowledge_base_id
FROM ai_chat_session
WHERE deleted = 0 AND knowledge_base_id IS NOT NULL;
