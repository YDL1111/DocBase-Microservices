package com.docbase.iam.auth.dto;

public record UserInfo(
        Long userId,
        String username,
        String nickname,
        String email,
        String phoneNumber,
        boolean admin
) {
}
