package com.docbase.common.security;

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
import java.util.List;

/**
 * Verifies RS256 JWT access tokens using a public key. Used by Gateway and
 * business services. Never holds a private key.
 */
@Component
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);
    private static final List<String> REQUIRED_CLAIMS = List.of("sub", "jti", "token_type");

    private final JwtProperties properties;
    private final ResourceLoader resourceLoader;
    private RSAPublicKey publicKey;

    public JwtVerifier(JwtProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        if (properties.publicKeyPath() == null || properties.publicKeyPath().isBlank()) {
            log.warn("jwt.public-key-path is not configured; JWT verification is disabled");
            return;
        }
        try {
            this.publicKey = loadPublicKey(properties.publicKeyPath());
            log.info("JWT verifier initialized with public key: {}", properties.publicKeyPath());
        } catch (Exception e) {
            log.warn("Failed to load JWT public key from {}: {}; JWT verification is disabled",
                    properties.publicKeyPath(), e.getMessage());
            this.publicKey = null;
        }
    }

    public boolean isEnabled() {
        return publicKey != null;
    }

    /**
     * Parses and verifies an access token. Returns {@code null} when verification is
     * disabled or the token is invalid/expired/wrong-issuer/wrong-type.
     */
    public Claims verify(String bearerToken) {
        return verify(bearerToken, "access");
    }

    /**
     * Parses and verifies a JWT token with expected type. Returns {@code null} when
     * verification is disabled or the token is invalid/expired/wrong-issuer/wrong-type.
     */
    public Claims verify(String bearerToken, String expectedTokenType) {
        if (publicKey == null || bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(bearerToken)
                    .getPayload();
            if (!expectedTokenType.equals(claims.get("token_type"))) {
                log.debug("Rejected token: expected type {}, got {}", expectedTokenType, claims.get("token_type"));
                return null;
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT verification failed: {}", e.getMessage());
            return null;
        }
    }

    private RSAPublicKey loadPublicKey(String path) {
        try {
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load JWT public key from: " + path, e);
        }
    }
}
