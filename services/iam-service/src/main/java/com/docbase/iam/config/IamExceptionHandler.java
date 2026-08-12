package com.docbase.iam.config;

import com.docbase.common.core.ApiResponse;
import jakarta.validation.ConstraintViolationException;
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

    /**
     * 处理控制器参数约束校验失败（@Min/@Max/@Valid 在 @RequestParam / @PathVariable 上），
     * 返回 400 VALIDATION_ERROR 而非 500。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .orElse("request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }
}
