package com.docbase.iam.user.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request DTO for creating a user. Both username and password are required
 * (@NotBlank) because a new account must be created with credentials.
 */
public record CreateUserRequest(
        @NotBlank String username,
        String nickname,
        @NotBlank String password,
        String email,
        String phoneNumber,
        Integer sex,
        Integer status,
        String remark,
        List<Long> roleIds) {
}
