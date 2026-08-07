package com.docbase.chat.rag;

/**
 * Exception thrown when the RAG service returns an error or becomes unavailable.
 * The message is a safe, user-facing description; internal details are logged separately.
 */
public class RagStreamException extends RuntimeException {

    private final String errorCode;

    public RagStreamException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
