package com.docbase.iam.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secret required by the anonymous first-admin setup endpoint.
 *
 * <p>The key is deliberately externalized and has no default. A fresh deployment
 * therefore cannot be claimed through the public endpoint until its operator
 * explicitly configures {@code IAM_ADMIN_SETUP_KEY}.</p>
 */
@ConfigurationProperties(prefix = "iam.admin-setup")
public record AdminSetupProperties(String key) {

    private static final int MIN_KEY_LENGTH = 32;
    private static final int MAX_KEY_LENGTH = 256;

    public AdminSetupProperties {
        if (key != null && !key.isBlank()
                && (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH)) {
            throw new IllegalArgumentException(
                    "iam.admin-setup.key must be blank (disabled) or 32-256 characters");
        }
    }

    public boolean enabled() {
        return key != null && !key.isBlank();
    }
}
