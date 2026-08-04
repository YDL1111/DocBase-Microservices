package com.docbase.knowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeDocumentAcl;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentAclMapper extends BaseMapper<KnowledgeDocumentAcl> {

    /**
     * Soft delete all ACLs of a knowledge base.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document_acl SET deleted = 1, delete_marker = id WHERE knowledge_base_id = #{baseId} AND deleted = 0")
    int softDeleteByBaseId(Long baseId);

    /**
     * Soft delete all ACLs of a specific document.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_document_acl SET deleted = 1, delete_marker = id WHERE document_id = #{documentId} AND deleted = 0")
    int softDeleteByDocumentId(Long documentId);
}
