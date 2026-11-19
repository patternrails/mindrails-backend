package com.mindrails.backend.image;

import com.mindrails.backend.common.ServiceResult.ErrorCode;

public final class ImageGenerationException extends RuntimeException {
    private final ErrorCode code;
    public ImageGenerationException(ErrorCode code, String message) { super(message); this.code = code; }
    public ErrorCode code() { return code; }
    public static ImageGenerationException invalid(String message) {
        return new ImageGenerationException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
