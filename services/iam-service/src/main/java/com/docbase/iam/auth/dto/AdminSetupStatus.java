package com.docbase.iam.auth.dto;

/** Public, non-sensitive state used by the login page to render first-run setup. */
public record AdminSetupStatus(boolean required, boolean enabled) {
}
