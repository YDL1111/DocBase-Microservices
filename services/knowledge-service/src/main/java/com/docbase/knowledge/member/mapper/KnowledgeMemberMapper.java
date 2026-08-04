package com.docbase.knowledge.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeMemberMapper extends BaseMapper<KnowledgeMember> {

    /**
     * Soft delete all members of a knowledge base.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_member SET deleted = 1, delete_marker = id WHERE knowledge_base_id = #{baseId} AND deleted = 0")
    int softDeleteByBaseId(Long baseId);

    /**
     * Soft delete a single member by ID.
     * Sets both deleted=1 and delete_marker=id to maintain unique constraint.
     */
    @Update("UPDATE knowledge_member SET deleted = 1, delete_marker = id WHERE id = #{id} AND deleted = 0")
    int softDeleteById(Long id);
}
