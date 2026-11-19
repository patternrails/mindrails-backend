package com.mindrails.backend.provider;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes a tiny real request, outside Agent/session history, with a bounded deadline. */
public final class ProviderConnectionTest {
    public record Result(boolean connected, long latencyMs, String message) {}

    private ProviderConnectionTest() {}

    public static Result run(Runnable probe) {
        long start = System.nanoTime();
        FutureTask<Void> task = new FutureTask<>(probe, null);
        Thread.ofVirtual().start(task);
        boolean connected = false;
        String message;
        try {
            task.get(30, TimeUnit.SECONDS);
            connected = true;
            message = "Connected";
        } catch (TimeoutException timeout) {
            message = "Connection timed out (30 seconds)";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            message = "Connection test interrupted";
        } catch (ExecutionException error) {
            // Remote exceptions may include request headers or response bodies containing secrets.
            message = "Connection failed. Check the API key, endpoint and model.";
        } finally {
            task.cancel(true);
        }
        return new Result(
                connected, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), message);
    }

    public static void language(Provider provider) {
        var messages =
                List.<dev.langchain4j.data.message.ChatMessage>of(
                        UserMessage.from("Reply only with OK."));
        if (!Boolean.TRUE.equals(provider.getStreaming())) {
            if (provider.getChatModel() == null) throw new IllegalStateException("API key missing");
            var response = provider.getChatModel().chat(messages);
            if (response == null || response.aiMessage() == null)
                throw new IllegalStateException("Empty response");
            return;
        }
        if (provider.getStreamingChatModel() == null)
            throw new IllegalStateException("API key missing");
        var received = new CompletableFuture<Void>();
        var closed = new AtomicBoolean();
        try {
            provider.getStreamingChatModel()
                    .chat(
                            messages,
                            new StreamingChatResponseHandler() {
                                @Override
                                public void onPartialResponse(String text) {
                                    if (!text.isEmpty()) received.complete(null);
                                }

                                @Override
                                public void onPartialResponse(
                                        PartialResponse response, PartialResponseContext context) {
                                    if (!response.text().isEmpty() || closed.get()) {
                                        received.complete(null);
                                        context.streamingHandle().cancel();
                                    }
                                }

                                @Override
                                public void onPartialThinking(
                                        PartialThinking thinking, PartialThinkingContext context) {
                                    received.complete(null);
                                    context.streamingHandle().cancel();
                                }

                                @Override
                                public void onCompleteResponse(ChatResponse response) {
                                    received.complete(null);
                                }

                                @Override
                                public void onError(Throwable error) {
                                    received.completeExceptionally(error);
                                }
                            });
            received.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (ExecutionException error) {
            throw new IllegalStateException("Remote model failed");
        } finally {
            closed.set(true);
        }
    }
}
