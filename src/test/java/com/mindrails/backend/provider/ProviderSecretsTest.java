package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProviderSecretsTest {
    private static final String PROPERTY = "mindrails.provider.encryption-key";
    private static final String LEGACY_KEY = Base64.getEncoder().encodeToString(
            "legacy-codec-unit-test-fixture".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptsWithRandomNoncesAndRejectsWrongKeysOrTampering() {
        var codec = new ProviderSecrets(new MockEnvironment().withProperty(PROPERTY, LEGACY_KEY));
        String first = codec.encrypt("private-test-key");
        String second = codec.encrypt("private-test-key");
        assertTrue(first.startsWith("enc:v1:"));
        assertNotEquals(first, second);
        assertFalse(first.contains("private-test-key"));
        assertEquals("private-test-key", codec.decrypt(first));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ProviderSecrets(
                                        new MockEnvironment().withProperty(PROPERTY, "b3RoZXIta2V5"))
                                .decrypt(first));
        assertThrows(
                IllegalStateException.class,
                () -> codec.decrypt(first.substring(0, first.length() - 5) + "AAAA"));
        assertNull(codec.decrypt("${OLD_ENV_KEY}"));
        assertThrows(
                IllegalArgumentException.class, () -> ProviderSecrets.validate("${OLD_ENV_KEY}"));
        assertThrows(IllegalArgumentException.class, () -> ProviderSecrets.validate("key\nheader"));
    }

    @Test
    void requiresAConfiguredBase64Key() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderSecrets(new MockEnvironment()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSecrets(new MockEnvironment().withProperty(PROPERTY, " ")));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSecrets(new MockEnvironment().withProperty(PROPERTY, "not-base64")));
    }

    @Test
    void decryptsLegacyCiphertextWithTheBase64EncodedOriginalKey() {
        var codec = new ProviderSecrets(new MockEnvironment().withProperty(PROPERTY, LEGACY_KEY));
        assertEquals("legacy-test-value", codec.decrypt(
                "enc:v1:AAECAwQFBgcICQoLUXafJAt2y0vdCzfWk6736tvucqePNAAWiSlGxZ+VA506"));
    }
}
