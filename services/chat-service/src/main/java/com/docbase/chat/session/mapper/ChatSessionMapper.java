package com.docbase.chat.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.chat.session.domain.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Update("""
            UPDATE ai_chat_session
            SET deleted = 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{sessionId} AND user_id = #{userId} AND deleted = 0
            """)
    int softDeleteOwned(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Select("SELECT deleted FROM ai_chat_session WHERE id = #{sessionId}")
    Integer selectDeletedFlag(@Param("sessionId") Long sessionId);
}
