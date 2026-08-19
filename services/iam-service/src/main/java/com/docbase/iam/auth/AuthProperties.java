package com.docbase.iam.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Anonymous path whitelist for IAM security. These paths do not require authentication.
 */
@ConfigurationProperties(prefix = "iam.auth")
public record AuthProperties(
        List<String> anonymousPaths
) {
    public AuthProperties {
        if (anonymousPaths == null || anonymousPaths.isEmpty()) {
            anonymousPaths = List.of(
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/api/auth/ping",
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/setup"
            );
        }
    }
}
