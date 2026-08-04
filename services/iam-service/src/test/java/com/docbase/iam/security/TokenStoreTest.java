package com.docbase.iam.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenStoreTest {

    @Test
    void storesAndValidatesRefreshToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        TokenStore store = new TokenStore(redis);
        store.storeRefresh("jti-1", 42L, 1L, Duration.ofDays(7));
        verify(valueOps).set("docbase:iam:token:refresh:jti-1", "42:1", Duration.ofDays(7));
    }

    @Test
    void rejectsMismatchedSessionVersion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("docbase:iam:token:refresh:jti-1")).thenReturn("42:1");

        TokenStore store = new TokenStore(redis);
        assertFalse(store.validateRefresh("jti-1", 2L), "should reject when session version mismatches");
    }

    @Test
    void acceptsMatchingSessionVersion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("docbase:iam:token:refresh:jti-1")).thenReturn("42:1");

        TokenStore store = new TokenStore(redis);
        assertTrue(store.validateRefresh("jti-1", 1L));
    }

    @Test
    void revokesRefreshToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TokenStore store = new TokenStore(redis);
        store.revokeRefresh("jti-1");
        verify(redis).delete("docbase:iam:token:refresh:jti-1");
    }

    @Test
    void cachesAndEvictsPermissions() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.members("docbase:iam:permission:42"))
                .thenReturn(Set.of("system:user:list", "system:role:list"));

        TokenStore store = new TokenStore(redis);
        Set<String> perms = store.getCachedPermissions(42L);
        assertTrue(perms.contains("system:user:list"));
        assertTrue(perms.contains("system:role:list"));

        store.evictPermissions(42L);
        verify(redis).delete("docbase:iam:permission:42");
    }

    @Test
    void rejectsNullJti() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(validateRefresh_nullKey())).thenReturn(null);

        TokenStore store = new TokenStore(redis);
        assertFalse(store.validateRefresh(null, 1L));
    }

    // Helper to avoid null key issue with mock
    private String validateRefresh_nullKey() {
        return "docbase:iam:token:refresh:null";
    }

    @Test
    void sessionVersionOperations() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey("docbase:iam:token:session:42")).thenReturn(false);

        TokenStore store = new TokenStore(redis);
        long version = store.initSessionVersion(42L);
        assertEquals(1L, version);
        verify(valueOps).set("docbase:iam:token:session:42", "1", Duration.ofDays(30));
    }

    @Test
    void authVersionOperations() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey("docbase:iam:token:auth:42")).thenReturn(false);

        TokenStore store = new TokenStore(redis);
        long version = store.initAuthVersion(42L);
        assertEquals(1L, version);
        verify(valueOps).set("docbase:iam:token:auth:42", "1", Duration.ofDays(30));
    }
}
