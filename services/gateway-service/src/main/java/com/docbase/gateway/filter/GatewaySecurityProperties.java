package com.docbase.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Gateway security configuration: anonymous path whitelist.
 */
@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(
        List<String> anonymousPaths
) {
    public GatewaySecurityProperties {
        if (anonymousPaths == null || anonymousPaths.isEmpty()) {
            anonymousPaths = List.of(
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/api/auth/ping",
                    "/api/auth/login",
                    "/api/auth/refresh"
            );
        }
    }
}
