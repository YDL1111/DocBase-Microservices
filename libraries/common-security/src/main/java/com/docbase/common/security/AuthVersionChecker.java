package com.docbase.common.security;

/**
 * Optional interface for checking authorization version.
 * Services that have access to the IAM Redis can implement this to enable
 * immediate access token invalidation on logout/disable/password change.
 *
 * The default implementation always returns true (no invalidation check).
 */
public interface AuthVersionChecker {

    /**
     * Checks if the given auth version is still valid for the user.
     *
     * @param userId the user ID (from JWT subject)
     * @param tokenAuthVersion the auth version embedded in the token
     * @return true if the token's auth version matches the current version
     */
    boolean isAuthVersionValid(String userId, long tokenAuthVersion);

    /**
     * Default implementation that always returns true (no check).
     */
    AuthVersionChecker NO_OP = (userId, tokenAuthVersion) -> true;
}
