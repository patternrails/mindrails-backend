package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

class ProviderConnectionTestTest {
    @Test
    void exercisesSyncModelAndRedactsRemoteFailureDetails() {
        var model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        assertEquals(1, request.messages().size());
                        return ChatResponse.builder().aiMessage(AiMessage.from("OK")).build();
                    }
                };
        assertTrue(
                ProviderConnectionTest.run(
                                () ->
                                        ProviderConnectionTest.language(
                                                Provider.builder()
                                                        .streaming(false)
                                                        .chatModel(model)
                                                        .build()))
                        .connected());
        var failure =
                ProviderConnectionTest.run(
                        () -> {
                            throw new IllegalArgumentException("private-key");
                        });
        assertFalse(failure.connected());
        assertFalse(failure.message().contains("private-key"));
    }

    @Test
    void firstStreamChunkCancelsTheProbe() {
        var cancelled = new AtomicBoolean();
        var context = mock(PartialResponseContext.class, RETURNS_DEEP_STUBS);
        var handle = context.streamingHandle();
        doAnswer(
                        ignored -> {
                            cancelled.set(true);
                            return null;
                        })
                .when(handle)
                .cancel();
        var model =
                new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        handler.onPartialResponse(new PartialResponse("OK"), context);
                    }
                };
        assertTrue(
                ProviderConnectionTest.run(
                                () ->
                                        ProviderConnectionTest.language(
                                                Provider.builder()
                                                        .streaming(true)
                                                        .streamingChatModel(model)
                                                        .build()))
                        .connected());
        assertTrue(cancelled.get());
    }
}
