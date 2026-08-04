package com.docbase.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * Redis-backed implementation of AuthVersionChecker.
 * Checks the auth_version claim in the JWT against the current version stored in Redis.
 *
 * This enables immediate access token invalidation on:
 * - Logout
 * - User disable/enable
 * - Password change
 * - Role/menu permission changes
 *
 * Failure mode is configurable via failClosed:
 * - failClosed = false (default): Redis failure allows the token (fail-open, high availability)
 * - failClosed = true: Redis failure rejects the token (fail-closed, high security)
 */
public class RedisAuthVersionChecker implements AuthVersionChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthVersionChecker.class);

    private static final String AUTH_VERSION_PREFIX = "docbase:iam:token:auth:";

    private final StringRedisTemplate redisTemplate;
    private final boolean failClosed;

    /**
     * Creates a RedisAuthVersionChecker with fail-open behavior (default).
     */
    public RedisAuthVersionChecker(StringRedisTemplate redisTemplate) {
        this(redisTemplate, false);
    }

    /**
     * Creates a RedisAuthVersionChecker with configurable failure mode.
     *
     * @param redisTemplate the Redis template
     * @param failClosed if true, Redis failures reject the token (high security);
     *                   if false, Redis failures allow the token (high availability)
     */
    public RedisAuthVersionChecker(StringRedisTemplate redisTemplate, boolean failClosed) {
        this.redisTemplate = redisTemplate;
        this.failClosed = failClosed;
    }

    @Override
    public boolean isAuthVersionValid(String userId, long tokenAuthVersion) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            String key = AUTH_VERSION_PREFIX + userId;
            String currentVersion = redisTemplate.opsForValue().get(key);
            if (currentVersion == null) {
                // No version stored yet - token was issued before this check existed
                // or user has never logged in, or Redis data was cleared.
                // In fail-closed mode, reject the token to prevent revoked tokens
                // from becoming valid again after Redis data loss.
                if (failClosed) {
                    log.warn("auth_version key missing for user {}, rejecting token (fail-closed mode)", userId);
                    return false;
                }
                // Fail-open: accept the token (backward compatible)
                return true;
            }
            long current = Long.parseLong(currentVersion);
            return tokenAuthVersion == current;
        } catch (NumberFormatException e) {
            log.warn("Invalid auth_version format for user {}", userId);
            return false;
        } catch (Exception e) {
            // Redis unavailable - behavior depends on failClosed setting
            if (failClosed) {
                log.warn("Redis unavailable, rejecting token for user {} (fail-closed mode)", userId);
                return false;
            }
            log.warn("Redis unavailable, allowing token for user {} (fail-open mode): {}", userId, e.getMessage());
            return true;
        }
    }
}
