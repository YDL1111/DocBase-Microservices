package com.docbase.knowledge.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Update("UPDATE knowledge_base SET deleted = 1, delete_marker = #{id}, updated_by = #{userId} WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}
