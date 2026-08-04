package com.docbase.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IAM JWT signing properties. The private key is held only by iam-service.
 */
@ConfigurationProperties(prefix = "iam.jwt")
public record JwtProperties(
        String privateKeyPath,
        String issuer,
        String accessTtl,
        String refreshTtl
) {
}
