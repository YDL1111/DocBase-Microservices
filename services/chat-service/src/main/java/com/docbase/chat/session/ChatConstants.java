package com.docbase.chat.session;

/**
 * Constants for chat sessions and messages.
 */
public interface ChatConstants {

    // --- ai_chat_session.status ---
    int SESSION_STATUS_ACTIVE = 1;
    int SESSION_STATUS_ARCHIVED = 2;

    // --- ai_chat_message.role ---
    int MESSAGE_ROLE_USER = 1;
    int MESSAGE_ROLE_ASSISTANT = 2;
    int MESSAGE_ROLE_SYSTEM = 3;

    // --- ai_chat_message.status ---
    int MESSAGE_STATUS_STREAMING = 1;
    int MESSAGE_STATUS_COMPLETED = 2;
    int MESSAGE_STATUS_FAILED = 3;
    int MESSAGE_STATUS_CANCELLED = 4;

    // --- Limits ---
    /** Maximum length of a user question (characters). */
    int MAX_QUESTION_LENGTH = 4000;
    /** Maximum length of an assistant response content buffered in memory before truncation (characters). */
    int MAX_RESPONSE_LENGTH = 65536;
    /** Maximum length of a session title (characters). */
    int MAX_TITLE_LENGTH = 255;
    /** Maximum page size for session/message listing. */
    int MAX_PAGE_SIZE = 100;
    /** Default page size. */
    int DEFAULT_PAGE_SIZE = 20;
    /** Sliding conversation window sent to RAG (roughly six user/assistant turns). */
    int RAG_HISTORY_MAX_MESSAGES = 12;
    int RAG_HISTORY_MAX_CHARS = 12000;

    // --- Redis lock ---
    String STREAM_LOCK_KEY_PREFIX = "docbase:chat:stream:";
    long STREAM_LOCK_TTL_SECONDS = 120;
    int STREAM_MAX_CONCURRENT_PER_USER = 1;
}
