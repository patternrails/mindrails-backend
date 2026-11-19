package com.mindrails.backend.mcp.service.internal;

import com.mindrails.backend.mcp.model.McpConnectionConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption at rest, never a plaintext fallback. Only env/header values are
 * interpolated.
 */
@Component
public final class McpSecrets {
    public static final String KEEP = "__MINDRAILS_KEEP_SECRET__";

    @Autowired
    public McpSecrets(McpProperties properties) {
        this(properties.getEncryptionKey());
    }

    private static final String PREFIX = "sealed:v1:";
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final byte[] key;
    private final Function<String, String> environment;

    public McpSecrets(String encodedKey) {
        this(encodedKey, System::getenv);
    }

    public McpSecrets(String encodedKey, Function<String, String> environment) {
        this.environment = environment;
        try {
            key =
                    encodedKey == null || encodedKey.isBlank()
                            ? null
                            : Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("MCP encryption key must be base64");
        }
        if (key != null && key.length != 32) {
            throw new IllegalArgumentException("MCP encryption key must decode to 32 bytes");
        }
    }

    public McpConnectionConfig seal(McpConnectionConfig config, McpConnectionConfig previous) {
        return transform(
                config,
                (field, name, value) -> {
                    if (value.equals(KEEP)) {
                        String old =
                                previous == null
                                        ? null
                                        : (field.equals("env")
                                                        ? previous.env()
                                                        : previous.headers())
                                                .get(name);
                        if (old == null) {
                            throw new IllegalArgumentException(
                                    "No existing secret for " + field + "." + name);
                        }
                        return old;
                    }
                    if (value.startsWith(PREFIX)) {
                        throw new IllegalArgumentException("Reserved secret encoding");
                    }
                    // Only reference-only values and the conventional Bearer prefix may stay
                    // plaintext.
                    if (REFERENCE.matcher(value).find()) {
                        if (value.replaceAll("\\$\\{[A-Za-z_][A-Za-z0-9_]*}", "").contains("${")) {
                            throw new IllegalArgumentException("Unsupported variable syntax");
                        }
                        String literal = REFERENCE.matcher(value).replaceAll("");
                        return literal.isEmpty() || literal.equals("Bearer ")
                                ? value
                                : encrypt(value, field + "." + name);
                    }
                    if (value.contains("${")) {
                        throw new IllegalArgumentException(
                                "Use ${ENV_NAME} backend environment references");
                    }
                    return encrypt(value, field + "." + name);
                });
    }

    public McpConnectionConfig masked(McpConnectionConfig config) {
        return transform(config, (field, name, value) -> value.startsWith(PREFIX) ? KEEP : value);
    }

    public McpConnectionConfig resolve(McpConnectionConfig config) {
        return transform(
                config,
                (field, name, value) -> {
                    String decoded =
                            value.startsWith(PREFIX) ? decrypt(value, field + "." + name) : value;
                    var matcher = REFERENCE.matcher(decoded);
                    var result = new StringBuilder();
                    while (matcher.find()) {
                        String found = environment.apply(matcher.group(1));
                        if (found == null) {
                            throw new IllegalStateException(
                                    "Missing environment variable: " + matcher.group(1));
                        }
                        matcher.appendReplacement(
                                result, java.util.regex.Matcher.quoteReplacement(found));
                    }
                    return matcher.appendTail(result).toString();
                });
    }

    private String encrypt(String value, String aad) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Literal env/headers require MINDRAILS_MCP_ENCRYPTION_KEY; environment"
                            + " references need no key");
        }
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, aad);
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + data.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(data, 0, payload, nonce.length, data.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("MCP secret encryption failed");
        }
    }

    private String decrypt(String value, String aad) {
        if (key == null) {
            throw new IllegalStateException("MCP encryption key is unavailable");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            return new String(
                    cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(payload, 12), aad)
                            .doFinal(Arrays.copyOfRange(payload, 12, payload.length)),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("MCP secret decryption failed");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String aad) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private interface Value {
        String apply(String field, String name, String value);
    }

    private static McpConnectionConfig transform(McpConnectionConfig config, Value function) {
        return config.withSecrets(
                transformValues("env", config.env(), function),
                transformValues("headers", config.headers(), function));
    }

    private static Map<String, String> transformValues(
            String field, Map<String, String> values, Value function) {
        var result = new LinkedHashMap<String, String>();
        values.forEach((name, value) -> result.put(name, function.apply(field, name, value)));
        return Map.copyOf(result);
    }
}
