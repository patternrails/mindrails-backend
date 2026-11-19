package com.mindrails.backend.tool;

/** A tool-owned business error whose message is safe to return to the model. */
public class ToolFailure extends RuntimeException {
    public ToolFailure(String message) {
        super(message);
    }
}
