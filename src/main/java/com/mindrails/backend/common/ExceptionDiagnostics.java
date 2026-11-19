package com.mindrails.backend.common;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, redacted cause chains for console diagnostics. Never mutate the original exception. */
public final class ExceptionDiagnostics {
    private static final Pattern IMAGE_DATA = Pattern.compile("data:image/[^;\\s]+;base64,[A-Za-z0-9+/=]+",
            Pattern.CASE_INSENSITIVE);

    private ExceptionDiagnostics() {}

    public static Throwable forLogging(Throwable error) {
        return diagnostic(error, 0, Collections.newSetFromMap(new IdentityHashMap<>()), false);
    }

    public static Throwable withoutImagePayload(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && IMAGE_DATA.matcher(message).find()) return forLogging(error);
        }
        return error;
    }

    /** Compact cause details suitable for persisted processing errors and HTTP responses. */
    public static String summary(Throwable error) {
        var messages = new java.util.ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (int i = 0; error != null && i < 4 && seen.add(error); i++, error = error.getCause()) {
            String detail = error instanceof org.springframework.web.server.ResponseStatusException status
                    ? status.getReason() : error.getMessage();
            messages.add(error.getClass().getSimpleName() + ": " + safe(detail));
        }
        return safe(String.join(" → ", messages));
    }

    private static Throwable diagnostic(
            Throwable failure, int depth, Set<Throwable> seen, boolean omitBody) {
        if (failure == null || depth >= 8 || !seen.add(failure)) {
            return null;
        }
        omitBody |= failure instanceof HttpMessageNotReadableException;
        String detail;
        if (omitBody) {
            detail = "Request JSON could not be parsed/bound; body and rejected values omitted";
            if (failure instanceof tools.jackson.core.JacksonException json
                    && !json.getPath().isEmpty()) {
                detail +=
                        "; field="
                                + safe(
                                        json.getPath().stream()
                                                .map(
                                                        reference ->
                                                                reference.getPropertyName() != null
                                                                        ? reference
                                                                                .getPropertyName()
                                                                        : "["
                                                                                + reference
                                                                                        .getIndex()
                                                                                + "]")
                                                .collect(java.util.stream.Collectors.joining(".")));
            }
            if (failure instanceof tools.jackson.databind.exc.MismatchedInputException mismatch
                    && mismatch.getTargetType() != null) {
                detail += "; expected=" + mismatch.getTargetType().getSimpleName();
            }
        } else if (failure instanceof BindException binding) {
            detail =
                    binding.getFieldErrors().stream()
                            .map(error -> error.getField() + ":" + error.getCode())
                            .distinct()
                            .limit(20)
                            .collect(java.util.stream.Collectors.joining(", "));
        } else {
            detail = safe(failure.getMessage());
        }
        var copy =
                new RuntimeException(
                        failure.getClass().getName() + ": " + detail,
                        diagnostic(failure.getCause(), depth + 1, seen, omitBody));
        copy.setStackTrace(failure.getStackTrace());
        return copy;
    }

    public static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        String text =
                IMAGE_DATA.matcher(value).replaceAll("[REDACTED IMAGE]")
                        .replaceAll("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+", "$1 [REDACTED]")
                        .replaceAll(
                                "(?i)((?:api[-_]?key|access[-_]?key(?:[-_]?(?:id|secret))?|token|password|secret|authorization|credential|signature)[\\\"']?\\s*[=:]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)",
                                "$1[REDACTED]")
                        .replaceAll("\\bsk-[A-Za-z0-9_.-]+", "[REDACTED]")
                        .replaceAll("\\bLTAI[A-Za-z0-9]+", "[REDACTED]")
                        .replaceAll("(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
                        .replaceAll("(https?://[^\\s?#]+)\\?[^\\s]+", "$1?[REDACTED]")
                        .replaceAll("[\\r\\n\\t]", " ");
        return text.length() > 1500 ? text.substring(0, 1500) + "…" : text;
    }
}
