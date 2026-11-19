package com.mindrails.backend.identity;

public class IdentityException extends RuntimeException {
    final int status;
    public IdentityException(int status, String message) { super(message); this.status = status; }
    public static IdentityException unauthorized() { return new IdentityException(401, "Invalid or expired credentials"); }
    public static IdentityException forbidden() { return new IdentityException(403, "You do not have permission for this action"); }
}
