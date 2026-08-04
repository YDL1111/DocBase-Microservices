package com.docbase.iam.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    @Test
    void signsAccessTokensVerifiableWithPublicKey() throws Exception {
        var pair = TestKeys.generate();
        Path dir = TestKeys.writeTempKeyPair(pair);
        try {
            JwtProperties props = new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
            JwtTokenProvider provider = new JwtTokenProvider(props);
            provider.init();

            String access = provider.signAccess("42", "alice", Set.of("system:user:list"), 1L);
            assertNotNull(access);

            // Verify with the public key using jjwt directly
            Claims claims = Jwts.parser()
                    .verifyWith(loadPublicKey(dir.resolve("public.pem")))
                    .requireIssuer("docbase-iam")
                    .build()
                    .parseSignedClaims(access)
                    .getPayload();

            assertEquals("42", claims.getSubject());
            assertEquals("alice", claims.get("username"));
            assertEquals("access", claims.get("token_type"));
            assertNotNull(claims.getId());
            assertEquals(1L, ((Number) claims.get("auth_version")).longValue());
            assertNotNull(claims.get("permissions"));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void signsRefreshTokensWithCorrectType() throws Exception {
        var pair = TestKeys.generate();
        Path dir = TestKeys.writeTempKeyPair(pair);
        try {
            JwtProperties props = new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
            JwtTokenProvider provider = new JwtTokenProvider(props);
            provider.init();

            String refresh = provider.signRefresh("42", 1L);
            Claims claims = Jwts.parser()
                    .verifyWith(loadPublicKey(dir.resolve("public.pem")))
                    .build()
                    .parseSignedClaims(refresh)
                    .getPayload();

            assertEquals("42", claims.getSubject());
            assertEquals("refresh", claims.get("token_type"));
            assertEquals(1L, ((Number) claims.get("session_version")).longValue());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void wrongIssuerTokenFailsVerification() throws Exception {
        var pair = TestKeys.generate();
        Path dir = TestKeys.writeTempKeyPair(pair);
        try {
            JwtProperties props = new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "wrong-issuer", "30m", "7d");
            JwtTokenProvider provider = new JwtTokenProvider(props);
            provider.init();

            String token = provider.signAccess("1", "bob", Set.of(), 1L);

            assertThrows(Exception.class, () ->
                    Jwts.parser()
                            .verifyWith(loadPublicKey(dir.resolve("public.pem")))
                            .requireIssuer("docbase-iam")
                            .build()
                            .parseSignedClaims(token));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void parsesCustomTtlFormats() throws Exception {
        var pair = TestKeys.generate();
        Path dir = TestKeys.writeTempKeyPair(pair);
        try {
            JwtProperties props = new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "15m", "3d");
            JwtTokenProvider provider = new JwtTokenProvider(props);
            provider.init();

            assertEquals(Duration.ofMinutes(15), provider.accessTtl());
            assertEquals(Duration.ofDays(3), provider.refreshTtl());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void accessAndRefreshTokensDiffer() throws Exception {
        var pair = TestKeys.generate();
        Path dir = TestKeys.writeTempKeyPair(pair);
        try {
            JwtProperties props = new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
            JwtTokenProvider provider = new JwtTokenProvider(props);
            provider.init();

            String access = provider.signAccess("1", "user", Set.of("perm1"), 1L);
            String refresh = provider.signRefresh("1", 1L);
            assertNotEquals(access, refresh);
        } finally {
            cleanup(dir);
        }
    }

    private RSAPublicKey loadPublicKey(Path path) throws Exception {
        String content = Files.readString(path);
        String cleaned = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) factory.generatePublic(spec);
    }

    private void cleanup(Path dir) throws Exception {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }
}
