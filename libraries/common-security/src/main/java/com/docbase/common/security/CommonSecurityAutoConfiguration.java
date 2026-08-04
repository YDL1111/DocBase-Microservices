package com.docbase.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Common security technical capabilities shared across services:
 *   - JwtProperties (jwt.public-key-path, jwt.issuer)
 *   - JwtVerifier (public-key-only RS256 verification)
 *
 * The private key never lives here. This auto-configuration is opt-in for services
 * that need to verify tokens; iam-service uses its own signing configuration.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
@Import(JwtVerifier.class)
@ConditionalOnProperty(prefix = "jwt", name = "public-key-path")
public class CommonSecurityAutoConfiguration {
}
