package com.docbase.chat.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.chat.session.domain.ChatSessionKnowledgeBase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatSessionKnowledgeBaseMapper extends BaseMapper<ChatSessionKnowledgeBase> {
    @Select("SELECT knowledge_base_id FROM ai_chat_session_knowledge_base WHERE session_id = #{sessionId} ORDER BY id")
    List<Long> selectKnowledgeBaseIds(Long sessionId);

    @Delete("DELETE FROM ai_chat_session_knowledge_base WHERE session_id = #{sessionId}")
    int deleteBySessionId(Long sessionId);
}
