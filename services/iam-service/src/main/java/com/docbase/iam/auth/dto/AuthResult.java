package com.docbase.iam.auth.dto;

import java.util.Set;

public record AuthResult(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo userInfo,
        Set<String> permissions
) {
}
