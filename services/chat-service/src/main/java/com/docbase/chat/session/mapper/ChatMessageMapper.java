package com.docbase.chat.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.chat.session.domain.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * Finds an active user message by client request id within a session.
     * Used for client retry idempotency.
     * Implemented in XML/service layer to avoid H2 annotation quirks.
     */
    default ChatMessage selectActiveByClientRequestId(Long sessionId, String clientRequestId, Integer role) {
        List<ChatMessage> list = selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChatMessage>()
                .eq("session_id", sessionId)
                .eq("client_request_id", clientRequestId)
                .eq("role", role)
                .eq("deleted", 0));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Soft-deletes all messages of a session.
     */
    @Update("UPDATE ai_chat_message SET deleted = 1 WHERE session_id = #{sessionId} AND deleted = 0")
    int softDeleteBySessionId(@Param("sessionId") Long sessionId);

    /**
     * Selects all messages of a session including soft-deleted ones (bypasses logic-delete).
     */
    @Select("SELECT * FROM ai_chat_message WHERE session_id = #{sessionId}")
    List<ChatMessage> selectAllBySessionId(@Param("sessionId") Long sessionId);
}
