package com.docbase.iam.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{2,63}$") String username,
        @NotBlank @Size(max = 64) String nickname,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Email @Size(max = 128) String email) {
}
