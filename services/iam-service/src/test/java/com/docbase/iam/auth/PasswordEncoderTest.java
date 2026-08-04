package com.docbase.iam.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderTest {

    @Test
    void bcryptEncodesAndVerifies() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        String raw = "mySecurePassword123";
        String encoded = encoder.encode(raw);

        assertNotEquals(raw, encoded);
        assertTrue(encoded.startsWith("$2a$") || encoded.startsWith("$2b$") || encoded.startsWith("$2y$"));
        assertTrue(encoder.matches(raw, encoded));
        assertFalse(encoder.matches("wrong", encoded));
    }

    @Test
    void samePasswordProducesDifferentHashes() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash1 = encoder.encode("password");
        String hash2 = encoder.encode("password");
        assertNotEquals(hash1, hash2, "BCrypt should use random salt");
        assertTrue(encoder.matches("password", hash1));
        assertTrue(encoder.matches("password", hash2));
    }
}
