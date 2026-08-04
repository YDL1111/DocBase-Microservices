package com.docbase.iam.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed token and session state for IAM.
 *
 * Key conventions (prefix from Nacos: docbase:iam:token:):
 *   - docbase:iam:token:refresh:{jti}  -> refresh token state (userId:sessionVersion)
 *   - docbase:iam:token:session:{userId} -> per-user session version
 *   - docbase:iam:token:auth:{userId}    -> per-user authorization version (for access token invalidation)
 *   - docbase:iam:permission:{userId}    -> cached permission set
 *
 * Atomic operations:
 *   - Refresh token rotation uses a Lua script for atomic check-and-replace
 *   - Session/auth version bumps use INCR for atomic increments
 */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private static final String REFRESH_PREFIX = "docbase:iam:token:refresh:";
    private static final String SESSION_PREFIX = "docbase:iam:token:session:";
    private static final String AUTH_VERSION_PREFIX = "docbase:iam:token:auth:";
    private static final String PERMISSION_PREFIX = "docbase:iam:permission:";

    /**
     * Lua script for atomic refresh token rotation.
     * KEYS[1] = old refresh key, KEYS[2] = new refresh key
     * ARGV[1] = expected value (userId:version), ARGV[2] = new value, ARGV[3] = ttl seconds
     * Returns 1 if rotation succeeded, 0 if old token doesn't match (already used/tampered).
     */
    private static final String ROTATE_REFRESH_LUA = """
            local oldVal = redis.call('GET', KEYS[1])
            if oldVal ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> rotateRefreshScript;

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.rotateRefreshScript = new DefaultRedisScript<>(ROTATE_REFRESH_LUA, Long.class);
    }

    /**
     * Stores a refresh token's state. The state encodes the userId and the
     * current session version so that a password change or explicit logout can
     * invalidate it.
     */
    public void storeRefresh(String jti, Long userId, long sessionVersion, Duration ttl) {
        String value = userId + ":" + sessionVersion;
        redis.opsForValue().set(REFRESH_PREFIX + jti, value, ttl);
    }

    /**
     * Atomically rotates a refresh token: validates the old one matches expected value,
     * deletes it, and stores the new one. Returns true if rotation succeeded.
     * This prevents concurrent replay attacks.
     */
    public boolean rotateRefresh(String oldJti, Long userId, long expectedVersion, String newJti, Duration ttl) {
        String oldValue = userId + ":" + expectedVersion;
        String newValue = userId + ":" + expectedVersion;
        Long result = redis.execute(
                rotateRefreshScript,
                java.util.List.of(REFRESH_PREFIX + oldJti, REFRESH_PREFIX + newJti),
                oldValue,
                newValue,
                String.valueOf(ttl.getSeconds())
        );
        return result != null && result == 1L;
    }

    /**
     * Validates a refresh token exists and matches the expected session version.
     * Does NOT consume the token (use rotateRefresh for that).
     */
    public boolean validateRefresh(String jti, long currentSessionVersion) {
        String value = redis.opsForValue().get(REFRESH_PREFIX + jti);
        if (value == null) {
            return false;
        }
        return value.endsWith(":" + currentSessionVersion);
    }

    /**
     * Revokes a single refresh token.
     */
    public void revokeRefresh(String jti) {
        redis.delete(REFRESH_PREFIX + jti);
    }

    /**
     * Gets the current session version without modifying it.
     * Returns the current value, or 0 if not set.
     */
    public long getSessionVersion(Long userId) {
        String value = redis.opsForValue().get(SESSION_PREFIX + userId);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Initializes the session version to 1 if not already set.
     * Returns the current version (1 if newly initialized).
     */
    public long initSessionVersion(Long userId) {
        String key = SESSION_PREFIX + userId;
        Boolean exists = redis.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return getSessionVersion(userId);
        }
        redis.opsForValue().set(key, "1", Duration.ofDays(30));
        return 1L;
    }

    /**
     * Bumps the session version, invalidating all existing refresh tokens.
     * Uses INCR for atomic increment.
     */
    public long bumpSessionVersion(Long userId) {
        String key = SESSION_PREFIX + userId;
        Long version = redis.opsForValue().increment(key);
        if (version == null || version <= 0) {
            redis.opsForValue().set(key, "1", Duration.ofDays(30));
            return 1L;
        }
        redis.expire(key, Duration.ofDays(30));
        return version;
    }

    /**
     * Gets the current authorization version without modifying it.
     * This is checked during access token validation to detect logout/disable/password change.
     */
    public long getAuthVersion(Long userId) {
        String value = redis.opsForValue().get(AUTH_VERSION_PREFIX + userId);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Initializes the authorization version if not set.
     */
    public long initAuthVersion(Long userId) {
        String key = AUTH_VERSION_PREFIX + userId;
        Boolean exists = redis.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return getAuthVersion(userId);
        }
        redis.opsForValue().set(key, "1", Duration.ofDays(30));
        return 1L;
    }

    /**
     * Bumps the authorization version, invalidating all existing access tokens.
     */
    public long bumpAuthVersion(Long userId) {
        String key = AUTH_VERSION_PREFIX + userId;
        Long version = redis.opsForValue().increment(key);
        if (version == null || version <= 0) {
            redis.opsForValue().set(key, "1", Duration.ofDays(30));
            return 1L;
        }
        redis.expire(key, Duration.ofDays(30));
        return version;
    }

    public void cachePermissions(Long userId, Set<String> permissions, Duration ttl) {
        String key = PERMISSION_PREFIX + userId;
        redis.delete(key);
        if (permissions != null && !permissions.isEmpty()) {
            redis.opsForSet().add(key, permissions.toArray(new String[0]));
            redis.expire(key, ttl);
        }
    }

    public Set<String> getCachedPermissions(Long userId) {
        return redis.opsForSet().members(PERMISSION_PREFIX + userId);
    }

    public void evictPermissions(Long userId) {
        redis.delete(PERMISSION_PREFIX + userId);
    }

    /**
     * Evicts all permission caches using SCAN. Called when menu structure changes
     * globally (menu create/update/delete). Uses SCAN to avoid blocking Redis.
     */
    public void evictAllPermissions() {
        String pattern = PERMISSION_PREFIX + "*";
        var connection = redis.getConnectionFactory().getConnection();
        try {
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (var cursor = connection.keyCommands().scan(scanOptions)) {
                while (cursor.hasNext()) {
                    byte[] key = cursor.next();
                    connection.keyCommands().del(key);
                }
            }
        } finally {
            // Release the connection back to the pool
            if (connection instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Bumps all auth versions using SCAN. Called when menu structure changes
     * to invalidate all existing access tokens.
     */
    public void bumpAllAuthVersions() {
        String pattern = AUTH_VERSION_PREFIX + "*";
        var connection = redis.getConnectionFactory().getConnection();
        try {
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (var cursor = connection.keyCommands().scan(scanOptions)) {
                while (cursor.hasNext()) {
                    byte[] key = cursor.next();
                    connection.stringCommands().incr(key);
                }
            }
        } finally {
            // Release the connection back to the pool
            if (connection instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
