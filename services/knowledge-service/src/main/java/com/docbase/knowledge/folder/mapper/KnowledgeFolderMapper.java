package com.docbase.knowledge.folder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeFolderMapper extends BaseMapper<KnowledgeFolder> {

    /**
     * Soft delete all folders of a knowledge base.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_folder SET deleted = 1, delete_marker = id WHERE knowledge_base_id = #{baseId} AND deleted = 0")
    int softDeleteByBaseId(Long baseId);

    /**
     * Soft delete a single folder by ID.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_folder SET deleted = 1, delete_marker = id WHERE id = #{id} AND deleted = 0")
    int softDeleteById(Long id);
}
