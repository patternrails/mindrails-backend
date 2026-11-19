package com.mindrails.backend.mcp.service.internal;

import dev.langchain4j.exception.ToolExecutionException;

/** A definite remote error, distinct from a timeout or a lost response. */
public final class McpToolException extends RuntimeException {
    public McpToolException(String message) {
        super(message);
    }

    public static boolean isDefinite(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpToolException
                    || cause instanceof ToolExecutionException tool
                            && (tool.errorCode() != null || tool.getCause() == null)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasCause(Throwable error, Class<?> type) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    public static String rootMessage(Throwable error) {
        var cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
