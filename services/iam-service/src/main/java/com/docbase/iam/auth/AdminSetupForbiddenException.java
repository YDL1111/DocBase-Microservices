package com.docbase.iam.auth;

/** Raised when an anonymous first-admin request supplies an invalid setup key. */
public class AdminSetupForbiddenException extends RuntimeException {

    public AdminSetupForbiddenException() {
        super("invalid administrator setup key");
    }
}
