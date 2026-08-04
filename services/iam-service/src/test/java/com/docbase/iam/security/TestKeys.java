package com.docbase.iam.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generates an RSA key pair at runtime for tests. Keys are written to a temp
 * directory and never committed. This keeps tests independent of any real key file.
 */
public final class TestKeys {

    private TestKeys() {}

    public static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path writeTempKeyPair(KeyPair pair) throws IOException {
        Path dir = Files.createTempDirectory("iam-test-keys");
        String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + encodeBase64(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + encodeBase64(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path privateKey = dir.resolve("private.pem");
        Path publicKey = dir.resolve("public.pem");
        Files.writeString(privateKey, privatePem);
        Files.writeString(publicKey, publicPem);
        return dir;
    }

    private static String encodeBase64(byte[] data) {
        String base64 = Base64.getEncoder().encodeToString(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.toString().trim();
    }
}
