package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.memory.agent.AgentMemoryAccessException;
import com.openai.core.JsonObject;
import com.openai.core.JsonString;
import com.openai.errors.OpenAIServiceException;
import dev.langchain4j.exception.HttpException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeoutException;

/** Exposes safe provider and connection diagnostics for a failed turn. */
public final class ChatFailureMessages {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatFailureMessages() {}

    public static ChatStreamEvent.Error from(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null && depth < 8 && seen.add(failure);
                depth++, failure = failure.getCause()) {
            if (failure instanceof AgentMemoryAccessException memory) {
                return new ChatStreamEvent.Error(memory.code(), ExceptionDiagnostics.safe(memory.getMessage()));
            }
            if (failure instanceof AssetException asset) {
                var code = switch (asset.reason()) {
                    case INVALID_ARGUMENT -> ErrorCode.INVALID_ARGUMENT;
                    case NOT_FOUND -> ErrorCode.NOT_FOUND;
                    case CONFLICT -> ErrorCode.CONFLICT;
                    case NOT_IMPLEMENTED -> ErrorCode.NOT_IMPLEMENTED;
                    default -> ErrorCode.UNAVAILABLE;
                };
                return new ChatStreamEvent.Error(code, ExceptionDiagnostics.safe(asset.getMessage()));
            }
            if (failure instanceof SSLHandshakeException) {
                return connection(failure, "TLS_HANDSHAKE_FAILED",
                        "A secure connection to the AI service could not be established.");
            }
            if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException
                    || failure instanceof TimeoutException) {
                return connection(failure, "REQUEST_TIMEOUT", "The AI request timed out.");
            }
            if (failure instanceof UnknownHostException) {
                return connection(failure, "HOST_NOT_FOUND", "The upstream service address could not be resolved.");
            }
            if (failure instanceof ConnectException) {
                return connection(failure, "CONNECTION_FAILED", "The upstream service could not be reached.");
            }
            if (failure instanceof OpenAIServiceException provider) {
                String message = provider.body() instanceof JsonObject body
                        && body.values().get("message") instanceof JsonString text ? text.value() : null;
                return providerError(provider, provider.statusCode(), message);
            }
            if (failure instanceof HttpException provider) {
                return providerError(provider, provider.statusCode(), providerMessage(provider.getMessage()));
            }
        }
        return new ChatStreamEvent.Error(ErrorCode.INTERNAL_ERROR, "Session stream failed");
    }

    private static ChatStreamEvent.Error connection(Throwable failure, String reason, String message) {
        return new ChatStreamEvent.Error(ErrorCode.UNAVAILABLE, message, reason,
                failure.getClass().getSimpleName() + ": " + ExceptionDiagnostics.safe(failure.getMessage()));
    }

    private static ChatStreamEvent.Error providerError(Throwable failure, int status, String message) {
        var code = switch (status) {
            case 400, 413, 415, 422 -> ErrorCode.INVALID_ARGUMENT;
            default -> ErrorCode.UNAVAILABLE;
        };
        return new ChatStreamEvent.Error(code,
                message == null || message.isBlank() ? "The AI provider could not complete the request."
                        : ExceptionDiagnostics.safe(message),
                "PROVIDER_ERROR", failure.getClass().getName() + " (HTTP " + status + ")");
    }

    private static String providerMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            var json = JSON.readTree(body);
            var message = json.path("error").path("message");
            if (!message.isString()) message = json.path("message");
            return message.isString() ? message.asString() : null;
        } catch (JacksonException invalidBody) {
            return null;
        }
    }
}
