package com.docbase.iam.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.registration")
public record RegistrationProperties(boolean enabled, String defaultRoleKey) {
    public RegistrationProperties {
        defaultRoleKey = defaultRoleKey == null || defaultRoleKey.isBlank() ? "registered_user" : defaultRoleKey;
    }
}
