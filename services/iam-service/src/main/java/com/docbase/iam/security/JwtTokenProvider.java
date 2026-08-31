package com.docbase.iam.security;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;

/**
 * Signs RS256 JWT tokens using the IAM private key. Only iam-service holds the
 * private key; all other services verify with the public key.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtProperties properties;
    private RSAPrivateKey privateKey;
    private Duration accessTtl;
    private Duration refreshTtl;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        if (properties.privateKeyPath() == null || properties.privateKeyPath().isBlank()) {
            throw new IllegalStateException("iam.jwt.private-key-path is required for token signing");
        }
        this.privateKey = loadPrivateKey(properties.privateKeyPath());
        this.accessTtl = parseTtl(properties.accessTtl(), "PT30M");
        this.refreshTtl = parseTtl(properties.refreshTtl(), "P7D");
        log.info("JWT token provider initialized: issuer={}, accessTtl={}, refreshTtl={}",
                properties.issuer(), accessTtl, refreshTtl);
    }

    /**
     * Signs an access token with permissions and auth version claims.
     * The authVersion claim enables immediate invalidation on logout/disable/password change.
     */
    public String signAccess(String subject, String username, Collection<String> permissions, long authVersion) {
        return signAccess(subject, username, null, permissions, authVersion);
    }

    public String signAccess(String subject, String username, Long organizationId,
                             Collection<String> permissions, long authVersion) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .id(UUID.randomUUID().toString())
                .claim("username", username)
                .claim("token_type", "access")
                .claim("permissions", permissions)
                .claim("auth_version", authVersion);
        if (organizationId != null) builder.claim("organization_id", organizationId);
        return builder.signWith(privateKey).compact();
    }

    /**
     * Signs a refresh token with session version claim.
     * The session version enables invalidation on password change.
     */
    public String signRefresh(String subject, long sessionVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtl)))
                .id(UUID.randomUUID().toString())
                .claim("token_type", "refresh")
                .claim("session_version", sessionVersion)
                .signWith(privateKey)
                .compact();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    private Duration parseTtl(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return Duration.parse(fallback);
        }
        try {
            return Duration.parse(value);
        } catch (Exception e) {
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.replace("m", "")));
            }
            if (value.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(value.replace("d", "")));
            }
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.replace("h", "")));
            }
            return Duration.parse(fallback);
        }
    }

    private RSAPrivateKey loadPrivateKey(String path) {
        try {
            String content = Files.readString(Paths.get(path));
            String cleaned = content
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            try {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
                KeyFactory factory = KeyFactory.getInstance("RSA");
                return (RSAPrivateKey) factory.generatePrivate(spec);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Private key is not PKCS#8 format. Regenerate with: " +
                        "openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in private.pem -out private.pem", e);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load JWT private key from: " + path, e);
        }
    }
}
