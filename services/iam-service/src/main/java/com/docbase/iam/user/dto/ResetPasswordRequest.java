package com.docbase.iam.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for resetting a user's password.
 *
 * <p>Applies the same server-side password policy as user creation: the new
 * password must be non-blank and within the allowed length range. This blocks
 * null/blank/oversized passwords even when the caller bypasses the frontend.
 *
 * <p>The raw password is never logged or returned — it is only forwarded to
 * the service layer for hashing.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "password must not be blank")
        @Size(min = 6, max = 128, message = "password must be 6-128 characters")
        String password) {
}
