package com.mindrails.backend.identity;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordHash {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 128) return false;
        try {
            String[] parts = encoded.split("\\$");
            return parts.length == 4 && parts[0].equals("pbkdf2-sha256") &&
                    MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                            derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])));
        } catch (RuntimeException e) { return false; }
    }
    static void validate(String password) {
        if (password == null || password.length() < 12 || password.length() > 128 || password.isBlank())
            throw new IdentityException(400, "Password must contain 12–128 characters");
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
