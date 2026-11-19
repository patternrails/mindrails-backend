package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.memory.agent.AgentMemoryAccessException;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ChatFailureMessagesTest {
    @Test
    void unwrapsTheLoggedHandshakeFailureAndRedactsItsDiagnostic() {
        var cause = new SSLHandshakeException(
                "Remote host terminated the handshake; token=private-token Authorization: Bearer secret-value");
        var error = ChatFailureMessages.from(new CompletionException(new ExecutionException(cause)));
        assertEquals(ErrorCode.UNAVAILABLE, error.code());
        assertEquals("TLS_HANDSHAKE_FAILED", error.reason());
        assertTrue(error.detail().startsWith("SSLHandshakeException: Remote host terminated the handshake"));
        assertFalse(error.detail().contains("private-token"));
        assertFalse(error.detail().contains("secret-value"));
        assertFalse(error.detail().contains("CompletionException"));
    }

    @Test
    void identifiesConnectionAndTimeoutFailuresUnderAsyncWrappers() {
        assertEquals("REQUEST_TIMEOUT", ChatFailureMessages.from(
                new CompletionException(new HttpTimeoutException("request timed out"))).reason());
        assertEquals("CONNECTION_FAILED", ChatFailureMessages.from(new ConnectException("refused")).reason());
        assertEquals("HOST_NOT_FOUND", ChatFailureMessages.from(new UnknownHostException("example.test")).reason());
    }

    @Test
    void reportsTheProviderExceptionAndItsImagePatchRejection() {
        String message = "The image you provided requires 58806 patches after processing, "
                + "exceeding the limit of 30000. Please resize the image and try again.";
        var provider = BadRequestException.builder().headers(Headers.builder().build())
                .error(ErrorObject.builder().message(message)
                        .type("invalid_request_error").code("invalid_image").param("input").build())
                .build();

        var error = ChatFailureMessages.from(new CompletionException(provider));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
        assertEquals("PROVIDER_ERROR", error.reason());
        assertEquals(message, error.message());
        assertEquals("com.openai.errors.BadRequestException (HTTP 400)", error.detail());
    }

    @Test
    void onlyForwardsTheSanitizedMessageFromCompatibleHttpErrors() {
        var provider = new HttpException(400, """
                {"error":{"message":"Invalid image data:image/png;base64,AAAA; api_key=private-value",
                "debug":"internal response metadata"}}
                """);
        var error = ChatFailureMessages.from(new InvalidRequestException(provider));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
        assertEquals("Invalid image [REDACTED IMAGE]; api_key=[REDACTED]", error.message());
        assertEquals("dev.langchain4j.exception.HttpException (HTTP 400)", error.detail());
        assertEquals("The AI provider could not complete the request.",
                ChatFailureMessages.from(new HttpException(502, "<html>private response</html>")).message());
    }

    @Test
    void unknownFailuresDoNotExposeTheirMessageOrResponseBody() {
        var error = ChatFailureMessages.from(new CompletionException(
                new IllegalStateException("private provider response containing user data")));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertEquals("Session stream failed", error.message());
        assertNull(error.reason());
        assertNull(error.detail());
    }

    @Test
    void preservesSanitizedMemoryErrorsEvenWhenWrapped() {
        var error = ChatFailureMessages.from(new CompletionException(
                new AgentMemoryAccessException(ErrorCode.CONFLICT, "Memory changed")));
        assertEquals(ErrorCode.CONFLICT, error.code());
        assertEquals("Memory changed", error.message());
    }

    @Test
    void cyclicCauseChainsTerminate() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertNull(ChatFailureMessages.from(first).detail());
    }
}
