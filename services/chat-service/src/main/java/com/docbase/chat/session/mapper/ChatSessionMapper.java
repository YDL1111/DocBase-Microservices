package com.docbase.chat.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.chat.session.domain.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
