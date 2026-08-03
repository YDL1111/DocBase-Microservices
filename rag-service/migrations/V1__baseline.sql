CREATE TABLE rag_metadata (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_ref VARCHAR(128) NOT NULL,
    chroma_collection VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_knowledge_base_ref (knowledge_base_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
