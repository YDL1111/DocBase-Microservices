package com.docbase.knowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * Soft delete all documents of a knowledge base.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document SET deleted = 1, delete_marker = id WHERE knowledge_base_id = #{baseId} AND deleted = 0")
    int softDeleteByBaseId(Long baseId);

    /**
     * Soft delete a single document by ID.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document SET deleted = 1, delete_marker = id WHERE id = #{id} AND deleted = 0")
    int softDeleteById(Long id);
}
