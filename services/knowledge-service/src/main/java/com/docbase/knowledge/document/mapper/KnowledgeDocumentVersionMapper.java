package com.docbase.knowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocumentVersion;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentVersionMapper extends BaseMapper<KnowledgeDocumentVersion> {

    /**
     * Soft delete all versions belonging to documents in a knowledge base.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document_version SET deleted = 1, delete_marker = id " +
            "WHERE deleted = 0 AND document_id IN (SELECT id FROM knowledge_document WHERE knowledge_base_id = #{baseId})")
    int softDeleteByBaseId(Long baseId);

    /**
     * Soft delete all versions of a specific document.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document_version SET deleted = 1, delete_marker = id WHERE document_id = #{documentId} AND deleted = 0")
    int softDeleteByDocumentId(Long documentId);
}
