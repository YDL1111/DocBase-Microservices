package com.docbase.iam.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request for the one-time creation of the first active super administrator. */
public record AdminSetupRequest(
        @NotBlank(message = "setupKey is required")
        @Size(min = 32, max = 256, message = "setupKey must be 32-256 characters")
        String setupKey,

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 64, message = "username must be 3-64 characters")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]*$",
                message = "username must start with a letter and contain only letters, numbers, '.', '_' or '-'")
        String username,

        @NotBlank(message = "nickname is required")
        @Size(max = 64, message = "nickname must not exceed 64 characters")
        String nickname,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be 8-72 characters")
        String password) {
}
