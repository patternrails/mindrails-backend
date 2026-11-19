package com.mindrails.backend.tool;

import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class ToolErrorHandlerTest {
    @org.junit.jupiter.api.Test
    void handlersAreAvailableRegardlessOfMemoryAndKnowledgeSwitches() {
        new ApplicationContextRunner().withUserConfiguration(ToolErrorHandler.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ToolErrorHandler.class)
                        .doesNotHaveBean(ToolArgumentsErrorHandler.class)
                        .doesNotHaveBean(ToolExecutionErrorHandler.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryToolsCanRepairArgumentErrorsWithoutMemoryEnabled(boolean streaming) {
        var tool = new TestTool();
        var step = new AtomicInteger();
        var session = session(streaming, request -> switch (step.getAndIncrement()) {
            case 0 -> call("echo", "{\"value\":\"invalid\"}");
            case 1 -> {
                var result = lastTool(request);
                assertEquals(true, result.isError());
                assertEquals("INVALID_ARGUMENT", new JsonMapper().readTree(result.text()).get("code").asText());
                assertFalse(result.text().contains("memory"));
                assertEquals(0, tool.calls);
                yield call("echo", "{\"value\":7}");
            }
            default -> {
                assertEquals("7", lastTool(request).text());
                yield reply(streaming);
            }
        });

        invoke(client(tool, session), session, streaming);

        assertEquals(3, step.get());
        assertEquals(1, tool.calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedExecutionErrorsReturnAGenericFailureAndAllowTheConversationToContinue(boolean streaming) {
        var step = new AtomicInteger();
        var session = session(streaming, request -> {
            if (step.getAndIncrement() == 0) return call("fail", "{}");
            var result = lastTool(request);
            assertEquals(true, result.isError());
            assertEquals("INTERNAL_ERROR", new JsonMapper().readTree(result.text()).get("code").asText());
            assertFalse(result.text().contains("private diagnostic"));
            assertFalse(result.text().contains("memory"));
            return reply(streaming);
        });

        invoke(client(new TestTool(), session), session, streaming);

        assertEquals(2, step.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationStopsTheInvocationInsteadOfReturningAToolErrorToTheModel(boolean streaming) {
        var calls = new AtomicInteger();
        var session = session(streaming, request -> {
            calls.incrementAndGet();
            return call("cancel", "{}");
        });
        var client = client(new TestTool(), session);

        if (streaming) {
            assertThrows(CancellationException.class, () -> client.chatWithStream(session.getMemoryId(), "Use tool")
                    .onError(error -> fail(error)).start());
        } else {
            assertThrows(CancellationException.class,
                    () -> TestLlmClients.chat(client, session.getMemoryId(), "Use tool"));
        }
        assertEquals(1, calls.get());
    }

    private LlmClient client(TestTool tool, Session session) {
        var result = TestMemory.factory(List.of(tool)).createClientForLoop(session);
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<LlmClient>) result).value();
    }

    private void invoke(LlmClient client, Session session, boolean streaming) {
        if (streaming) {
            var responses = new ArrayList<ChatResponse>();
            client.chatWithStream(session.getMemoryId(), "Use tool").onError(error -> fail(error))
                    .onCompleteResponse(responses::add).start();
            assertEquals("done", responses.getFirst().aiMessage().text());
        } else {
            assertEquals("[\"done\"]", TestLlmClients.chat(client, session.getMemoryId(), "Use tool").content());
        }
    }

    private Session session(boolean streaming, Function<ChatRequest, ChatResponse> model) {
        var provider = Provider.builder().streaming(streaming)
                .chatModel(new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) { return model.apply(request); }
                })
                .streamingChatModel(new StreamingChatModel() {
                    @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        handler.onCompleteResponse(model.apply(request));
                    }
                }).build();
        return Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).provider(provider).build();
    }

    private ChatResponse call(String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(name).arguments(arguments).build())).build();
    }

    private ChatResponse reply(boolean streaming) {
        return ChatResponse.builder().aiMessage(AiMessage.from(streaming ? "done" : "[\"done\"]")).build();
    }

    private ToolExecutionResultMessage lastTool(ChatRequest request) {
        return (ToolExecutionResultMessage) request.messages().getLast();
    }

    static class TestTool implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();
        int calls;

        @Override public UUID id() { return id; }

        @Tool public String echo(int value) { calls++; return Integer.toString(value); }
        @Tool public String fail() { throw new IllegalStateException("private diagnostic"); }

        @Tool public String cancel() { throw new CancellationException("Cancelled by the tool"); }
    }
}
