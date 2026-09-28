package com.example.ledgers.tenancy;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Key format: {@code lk_live_} + 32 random bytes (base64url); the prefix lets secret scanners spot leaked keys.
 * A 256-bit random key cannot be brute-forced, so plain SHA-256 (no salt, no bcrypt) is enough and doubles as the
 * lookup key.
 */
@UtilityClass
class ApiKeyCodec {

    private final String PREFIX = "lk_live_";
    private final SecureRandom RANDOM = new SecureRandom();

    String generate() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    @SneakyThrows
    byte[] hash(String key) {
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    }

    String hint(String key) {
        return key.substring(key.length() - 4);
    }
}
