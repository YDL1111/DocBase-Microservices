package com.docbase.iam.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * Verifies RS256 JWT tokens (both access and refresh) using the public key.
 * iam-service uses this to verify its own tokens during refresh, so it can
 * validate signature, issuer, expiry, and token_type without trusting the token
 * content blindly.
 */
@Component
public class JwtTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenVerifier.class);

    private final JwtProperties properties;
    private final ResourceLoader resourceLoader;
    private RSAPublicKey publicKey;

    public JwtTokenVerifier(JwtProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        String publicPath = resolvePublicKeyPath();
        if (publicPath == null || publicPath.isBlank()) {
            log.warn("JWT public key path not configured; token verification disabled");
            return;
        }
        try {
            this.publicKey = loadPublicKey(publicPath);
            log.info("JWT token verifier initialized with public key: {}", publicPath);
        } catch (Exception e) {
            log.warn("Failed to load JWT public key from {}: {}; verification disabled",
                    publicPath, e.getMessage());
            this.publicKey = null;
        }
    }

    public boolean isEnabled() {
        return publicKey != null;
    }

    /**
     * Fully verifies a JWT token: signature, issuer, expiry, and token_type.
     * Returns the claims if valid, null otherwise.
     */
    public Claims verify(String token, String expectedTokenType) {
        if (publicKey == null || token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Check expiry explicitly (jjwt does this, but double-check)
            if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
                log.debug("Token expired");
                return null;
            }

            // Verify token_type
            if (!expectedTokenType.equals(claims.get("token_type"))) {
                log.debug("Token type mismatch: expected {}, got {}", expectedTokenType, claims.get("token_type"));
                return null;
            }

            // Verify required claims
            if (claims.getSubject() == null || claims.getId() == null) {
                log.debug("Token missing required claims (sub, jti)");
                return null;
            }

            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT verification failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verifies a refresh token and returns its claims.
     */
    public Claims verifyRefresh(String token) {
        return verify(token, "refresh");
    }

    /**
     * Verifies an access token and returns its claims.
     */
    public Claims verifyAccess(String token) {
        return verify(token, "access");
    }

    private String resolvePublicKeyPath() {
        // Derive public key path from private key path
        String privateKeyPath = properties.privateKeyPath();
        if (privateKeyPath == null) return null;
        return privateKeyPath.replace("private", "public").replace("-private", "-public");
    }

    private RSAPublicKey loadPublicKey(String path) throws Exception {
        String content;
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            content = new String(resource.getInputStream().readAllBytes());
        } else {
            content = Files.readString(Paths.get(path));
        }
        String cleaned = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) factory.generatePublic(spec);
    }
}
