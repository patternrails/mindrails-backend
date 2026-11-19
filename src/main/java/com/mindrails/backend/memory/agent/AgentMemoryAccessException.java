package com.mindrails.backend.memory.agent;

import com.mindrails.backend.common.ServiceResult.ErrorCode;

/** Carries a sanitized memory failure through LC4J's request-preparation callback. */
public final class AgentMemoryAccessException extends RuntimeException {
    private final ErrorCode code;
    public AgentMemoryAccessException(ErrorCode code, String message) { super(message); this.code = code; }
    public ErrorCode code() { return code; }
}
