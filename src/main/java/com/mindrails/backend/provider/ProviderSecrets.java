package com.mindrails.backend.provider;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small shared credential codec for both provider catalogs. API responses never contain keys. */
public final class ProviderSecrets {
    private static final String PREFIX = "enc:v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ProviderSecrets(Environment environment) {
        String encoded = environment.getProperty("mindrails.provider.encryption-key");
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("MINDRAILS_PROVIDER_ENCRYPTION_KEY is required");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("MINDRAILS_PROVIDER_ENCRYPTION_KEY must be valid Base64");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static void validate(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 4096
                || !value.matches("[\\x21-\\x7E]+")
                || value.contains("${")
                || value.startsWith(PREFIX))
            throw new IllegalArgumentException(
                    "A valid API key is required (maximum 4096 characters)");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        validate(value);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt provider credential");
        }
    }

    public String decrypt(String value) {
        // Old catalogs remain readable, but environment references no longer resolve credentials.
        if (value == null || value.isBlank() || value.startsWith("${")) return null;
        if (!value.startsWith(PREFIX)) {
            validate(value);
            return value;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length < 29) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
            return new String(
                    cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to decrypt provider credential; check the encryption key");
        }
    }
}
