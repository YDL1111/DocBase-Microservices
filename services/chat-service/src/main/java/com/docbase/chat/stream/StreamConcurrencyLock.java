package com.docbase.chat.stream;

import com.docbase.chat.session.ChatConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis-backed per-user concurrency lock for AI chat streaming.
 *
 * <p>Each user may hold at most {@link ChatConstants#STREAM_MAX_CONCURRENT_PER_USER} concurrent
 * streaming requests. The lock has a TTL so a crashed request cannot block the user forever.
 *
 * <p>Lock ownership is identified by a random token. Release uses a compare-and-delete Lua script
 * so a request can only release its own lock — never a newer lock acquired by a different request.
 */
@Component
public class StreamConcurrencyLock {

    private static final Logger log = LoggerFactory.getLogger(StreamConcurrencyLock.class);

    /** compare-and-delete: only delete if the stored token equals our token */
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final SecureRandom random = new SecureRandom();

    public StreamConcurrencyLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    }

    /**
     * Acquires the lock for the user if free, returning a token. Returns null if the lock is
     * already held (concurrent stream limit reached).
     */
    public String tryAcquire(Long userId) {
        String key = key(userId);
        String token = generateToken();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, token, java.time.Duration.ofSeconds(ChatConstants.STREAM_LOCK_TTL_SECONDS));
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Stream lock acquired for user {}", userId);
            return token;
        }
        log.debug("Stream lock already held for user {}; concurrent stream rejected", userId);
        return null;
    }

    /**
     * Releases the lock only if the given token still matches the stored value.
     * @return true if the lock was released by this call
     */
    public boolean release(Long userId, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(
                    releaseScript, Collections.singletonList(key(userId)), token);
            boolean released = result != null && result == 1L;
            if (released) {
                log.debug("Stream lock released for user {}", userId);
            } else {
                log.debug("Stream lock release noop for user {} (token mismatch or expired)", userId);
            }
            return released;
        } catch (DataAccessException e) {
            log.warn("Redis error releasing stream lock for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Runs the given action while holding the lock, then releases it.
     * If the lock cannot be acquired, runs the rejectedAction instead.
     */
    public <T> T withLock(Long userId, Supplier<T> action, Supplier<T> rejectedAction) {
        String token = tryAcquire(userId);
        if (token == null) {
            return rejectedAction.get();
        }
        try {
            return action.get();
        } finally {
            release(userId, token);
        }
    }

    private String key(Long userId) {
        return ChatConstants.STREAM_LOCK_KEY_PREFIX + userId;
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
