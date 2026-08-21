package com.docbase.knowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /** Locks the active document row while a new ingest version is allocated. */
    @Select("SELECT * FROM knowledge_document WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    KnowledgeDocument selectActiveByIdForUpdate(@Param("id") Long id);

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

    /**
     * Updates the ingest status of a document.
     */
    @Update("UPDATE knowledge_document SET ingest_status = #{status} WHERE id = #{id} AND deleted = 0")
    int updateIngestStatus(@Param("id") Long id, @Param("status") Integer status);
}
