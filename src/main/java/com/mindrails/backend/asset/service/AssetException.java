package com.mindrails.backend.asset.service;

/** Business failure; transport status mapping belongs to api/web. */
public final class AssetException extends RuntimeException {
    public enum Reason {
        INVALID_ARGUMENT, NOT_FOUND, CONFLICT, NOT_IMPLEMENTED, UNAVAILABLE,
        PREVIEW_TOO_LARGE, PREVIEW_UNSUPPORTED
    }

    private final Reason reason;

    public AssetException(Reason reason, String message) {
        this(reason, message, null);
    }

    public AssetException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static AssetException invalid(String message) {
        return new AssetException(Reason.INVALID_ARGUMENT, message);
    }

    public static AssetException missing() {
        return new AssetException(Reason.NOT_FOUND, "Asset not found");
    }

    public static AssetException conflict(String message) {
        return new AssetException(Reason.CONFLICT, message);
    }

    public static AssetException disabled() {
        return new AssetException(Reason.NOT_IMPLEMENTED, "Asset Storage is disabled");
    }

    public static AssetException unavailable(String message, Throwable cause) {
        return new AssetException(Reason.UNAVAILABLE, message, cause);
    }
}
