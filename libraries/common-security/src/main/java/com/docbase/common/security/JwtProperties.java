package com.docbase.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Common JWT verification properties shared by Gateway and business services.
 * Only the public key is configured here; the private key never leaves iam-service.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String publicKeyPath,
        String issuer,
        String accessHeader,
        Boolean failClosed
) {
    public JwtProperties {
        if (accessHeader == null || accessHeader.isBlank()) {
            accessHeader = "Authorization";
        }
        if (failClosed == null) {
            failClosed = false; // Default: fail-open for high availability
        }
    }
}
