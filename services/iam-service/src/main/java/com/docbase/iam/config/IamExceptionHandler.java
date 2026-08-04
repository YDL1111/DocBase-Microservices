package com.docbase.iam.config;

import com.docbase.common.core.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IAM-specific exception handlers. Handles authentication errors with proper
 * 401 status codes. Runs with higher precedence than the global handler.
 */
@RestControllerAdvice
public class IamExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("BAD_CREDENTIALS", exception.getMessage()));
    }
}
