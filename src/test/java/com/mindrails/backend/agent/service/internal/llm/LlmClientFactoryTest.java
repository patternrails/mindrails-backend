package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.LlmClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultAiServiceCompletedListener;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultAiServiceErrorListener;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultAiServiceRequestIssuedListener;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultAiServiceResponseReceivedListener;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultAiServiceStartedListener;
import com.mindrails.backend.agent.service.internal.llm.listener.DefaultToolExecutedEventListener;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class LlmClientFactoryTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsFreshLoopClientsForEitherProvider(boolean streaming) {
        var syncModel = new RecordingChatModel();
        var streamModel = new RecordingStreamingChatModel();
        var session = streaming ? streamingSession(streamModel) : session(syncModel);
        var factory = TestMemory.factory(List.of());
        var client = value(factory.createClientForLoop(session));
        assertNotSame(client, value(factory.createClientForLoop(session)));
        assertNotNull(client.getChatMemory(dev.langchain4j.service.memory.ChatMemoryService.DEFAULT));
        var responses = new ArrayList<ChatResponse>();
        client.chatWithStream(session.getMemoryId(), "First", LlmClient.parameters(session, UUID.randomUUID(), "WEBSOCKET"))
                .onCompleteResponse(responses::add).onError(error -> fail(error)).start();
        session.setSystemMessage("Updated instructions");

        var updated = value(factory.createClientForLoop(session));
        assertNotSame(client, updated);
        updated.chatWithStream(session.getMemoryId(), "Second", LlmClient.parameters(session, UUID.randomUUID(), "WEBSOCKET"))
                .onCompleteResponse(responses::add).onError(error -> fail(error)).start();
        assertEquals(2, responses.size());
        var requests = streaming ? streamModel.requests : syncModel.requests;
        assertEquals(2, requests.size());
        assertEquals(dev.langchain4j.data.message.SystemMessage.from("Updated instructions"),
                requests.getLast().messages().getFirst());

        assertNotSame(updated, value(factory.createClientForLoop(session)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsLiteralSessionInstructionsOnEveryTurnWithoutLeakingAcrossSessions(boolean streaming) {
        RecordingChatModel syncModel = new RecordingChatModel();
        RecordingStreamingChatModel streamModel = new RecordingStreamingChatModel();
        List<ChatRequest> requests = streaming ? streamModel.requests : syncModel.requests;
        LlmClientFactory factory = TestMemory.factory(List.of());
        Session first = streaming ? streamingSession(streamModel) : session(syncModel);
        Session second = streaming ? streamingSession(streamModel) : session(syncModel);
        String instructions = "用中文回答。保留示例 {{name}} 和换行。\nCompare before booking.";
        first.setSystemMessage(instructions);
        second.setSystemMessage("Other session instructions");

        invoke(value(factory.createClientForLoop(first)), first.getMemoryId(), streaming, "First message");
        invoke(value(factory.createClientForLoop(first)), first.getMemoryId(), streaming, "Follow-up message");
        invoke(value(factory.createClientForLoop(second)), second.getMemoryId(), streaming, "Other message");

        assertEquals(3, requests.size());
        for (int index = 0; index < requests.size(); index++) {
            var systemMessages = requests.get(index).messages().stream()
                    .filter(dev.langchain4j.data.message.SystemMessage.class::isInstance)
                    .map(dev.langchain4j.data.message.SystemMessage.class::cast).toList();
            assertEquals(1, systemMessages.size());
            assertEquals(index < 2 ? instructions : "Other session instructions", systemMessages.getFirst().text());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omitsNullAndBlankSessionInstructions(boolean streaming) {
        for (String instructions : new String[] {null, "", " \n\t"}) {
            RecordingChatModel syncModel = new RecordingChatModel();
            RecordingStreamingChatModel streamModel = new RecordingStreamingChatModel();
            Session session = streaming ? streamingSession(streamModel) : session(syncModel);
            session.setSystemMessage(instructions);

            invoke(value(TestMemory.factory(List.of()).createClientForLoop(session)), session.getMemoryId(), streaming, "Hello");

            List<ChatRequest> requests = streaming ? streamModel.requests : syncModel.requests;
            assertTrue(requests.getFirst().messages().stream()
                    .noneMatch(dev.langchain4j.data.message.SystemMessage.class::isInstance));
        }
    }

    private void invoke(LlmClient client, UUID memoryId, boolean streaming, String message) {
        if (streaming) {
            client.chatWithStream(memoryId, message).onError(error -> fail(error)).start();
        } else {
            TestLlmClients.chat(client, memoryId, message);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritsAgentInstructionsAndUsesSessionOverridesAfterClientRebuild(boolean streaming) {
        RecordingChatModel syncModel = new RecordingChatModel();
        RecordingStreamingChatModel streamModel = new RecordingStreamingChatModel();
        List<ChatRequest> requests = streaming ? streamModel.requests : syncModel.requests;
        Provider provider = Provider.builder().id(UUID.randomUUID()).streaming(streaming)
                .chatModel(streaming ? null : syncModel).streamingChatModel(streaming ? streamModel : null).build();
        LlmClientFactory factory = TestMemory.factory(List.of());
        DefaultAgentService service = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                new DefaultSessionService(
                        new SessionRegistryConfiguration().sessionRegistry()), List.of(provider));
        UUID agentId = value(service.create(Agent.builder().name("Agent").systemMessage("Inherited instruction").build()));
        UUID sessionId = value(service.startSession(agentId, provider.getId()));
        Session session = value(service.listSessions(agentId, 0, 20)).getFirst();
        LlmClient original = value(factory.createClientForLoop(session));
        invoke(original, session.getMemoryId(), streaming, "Hello");
        value(service.update(agentId, Agent.builder().name("Agent").systemMessage("Changed Agent instruction").build()));
        invoke(value(factory.createClientForLoop(session)), session.getMemoryId(), streaming, "Follow-up");
        value(service.updateSession(agentId, sessionId, session.toBuilder().systemMessage("Session override").build(), provider.getId()));
        LlmClient rebuilt = value(factory.createClientForLoop(session));
        assertNotSame(original, rebuilt);
        invoke(rebuilt, session.getMemoryId(), streaming, "After update");
        value(service.updateSession(agentId, sessionId, session.toBuilder().systemMessage(null).build(), provider.getId()));
        invoke(value(factory.createClientForLoop(session)), session.getMemoryId(), streaming, "After clearing instructions");

        assertEquals(4, requests.size());
        assertEquals(dev.langchain4j.data.message.SystemMessage.from("Inherited instruction"), requests.get(0).messages().getFirst());
        assertEquals(dev.langchain4j.data.message.SystemMessage.from("Inherited instruction"), requests.get(1).messages().getFirst());
        assertEquals(dev.langchain4j.data.message.SystemMessage.from("Session override"), requests.get(2).messages().getFirst());
        assertEquals(2, requests.get(2).messages().size());
        assertTrue(requests.get(3).messages().stream().noneMatch(dev.langchain4j.data.message.SystemMessage.class::isInstance));
    }

    @Test
    void createsIndependentClientsWithoutCaching() {
        RecordingChatModel model = new RecordingChatModel();
        LlmClientFactory factory = TestMemory.factory(List.of());
        Session first = session(model);
        Session second = session(model);

        LlmClient firstClient = value(factory.createClientForLoop(first));
        LlmClient secondClient = value(factory.createClientForLoop(second));

        assertNotNull(firstClient);
        assertNotSame(firstClient, value(factory.createClientForLoop(first)));
        assertNotSame(firstClient, value(factory.createClientForLoop(first)));
        assertNotSame(secondClient, value(factory.createClientForLoop(second)));
        assertNotSame(firstClient, secondClient);
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void createsFreshClientsEvenForTheSameSessionSnapshot() {
        LlmClientFactory factory = TestMemory.factory(List.of());
        RecordingChatModel model = new RecordingChatModel();
        Session session = session(model);
        LlmClient existing = value(factory.createClientForLoop(session));

        assertNotSame(existing, value(factory.createClientForLoop(session.clone())));

        assertNotSame(existing, value(factory.createClientForLoop(session)));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void recreatedClientsUseUpdatedInstructionsAndTheSamePersistedMemory() {
        var store = new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
        var factory = TestLlmClients.create(List.of(), () -> store, new com.mindrails.backend.memory.ChatMemoryProperties());
        var model = new RecordingChatModel();
        var session = session(model).toBuilder().systemMessage("Respond in English.").build();
        var first = value(factory.createClientForLoop(session));
        TestLlmClients.chat(first, session.getMemoryId(), "First turn");
        var updated = session.toBuilder().systemMessage("Respond in Simplified Chinese.").build();
        var second = value(factory.createClientForLoop(updated));
        assertNotSame(first, second);
        assertNotSame(second, value(factory.createClientForLoop(updated.clone())));
        TestLlmClients.chat(second, updated.getMemoryId(), "Next turn");
        assertTrue(model.requests.getLast().messages().stream().anyMatch(message ->
                message instanceof dev.langchain4j.data.message.SystemMessage system
                        && system.text().contains("Simplified Chinese")));
        assertTrue(model.requests.getLast().messages().contains(UserMessage.from("First turn")));
    }

    @Test
    void concurrentCreationsDoNotShareClientInstances() throws Exception {
        var factory = TestMemory.factory(List.of());
        var session = session(new RecordingChatModel());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 16).mapToObj(ignored -> workers.submit(() -> {
                start.await();
                return value(factory.createClientForLoop(session.clone()));
            })).toList();
            start.countDown();
            var instances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LlmClient, Boolean>());
            for (var future : futures) instances.add(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(16, instances.size());
        }
    }

    @Test
    void streamingClientsRetainTheirOwnConversationHistory() {
        LlmClientFactory factory = TestMemory.factory(List.of());
        RecordingStreamingChatModel model = new RecordingStreamingChatModel();
        Session first = streamingSession(model);
        Session second = streamingSession(model);
        LlmClient firstClient = value(factory.createClientForLoop(first));
        LlmClient secondClient = value(factory.createClientForLoop(second));
        assertNotSame(firstClient, value(factory.createClientForLoop(first)));
        assertNotSame(firstClient, value(factory.createClientForLoop(first)));
        assertNotSame(firstClient, secondClient);
        assertTrue(model.requests.isEmpty());

        List<String> partials = new ArrayList<>();
        List<ChatResponse> completed = new ArrayList<>();
        firstClient.chatWithStream(first.getMemoryId(), "First message")
                .onPartialResponse(partials::add)
                .onCompleteResponse(completed::add)
                .onError(error -> fail(error))
                .start();
        firstClient.chatWithStream(first.getMemoryId(), "Follow-up message").onError(error -> fail(error)).start();
        secondClient.chatWithStream(second.getMemoryId(), "Other session").onError(error -> fail(error)).start();

        assertEquals(List.of("re", "ply"), partials);
        assertEquals("reply", completed.getFirst().aiMessage().text());
        assertEquals(3, model.requests.size());
        assertEquals(1, model.requests.getFirst().messages().size());
        assertEquals(3, model.requests.get(1).messages().size());
        assertEquals(AiMessage.from("reply"), model.requests.get(1).messages().get(1));
        assertEquals(1, model.requests.getLast().messages().size());
        assertEquals("Other session", ((UserMessage) model.requests.getLast().messages().getFirst()).singleText());
    }

    @Test
    void rejectsProvidersMissingTheSelectedModelWithoutFallingBack() {
        LlmClientFactory factory = TestMemory.factory(List.of());
        for (boolean streaming : List.of(false, true)) {
            Session session = Session.builder().memoryId(UUID.randomUUID()).provider(Provider.builder().streaming(streaming)
                    .chatModel(streaming ? mock(ChatModel.class) : null)
                    .streamingChatModel(streaming ? null : mock(StreamingChatModel.class)).build()).build();

            ServiceResult<LlmClient> result = factory.createClientForLoop(session);

            assertInstanceOf(ServiceResult.Failure.class, result);
            ServiceResult.Failure<LlmClient> failure = (ServiceResult.Failure<LlmClient>) result;
            assertEquals(ErrorCode.NOT_IMPLEMENTED, failure.code());
            assertEquals(streaming ? "Provider has no streaming chat model" : "Provider has no chat model",
                    failure.message());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTaskProvidersMissingTheSelectedModelWithoutFallingBack(boolean streaming) {
        var provider = Provider.builder().streaming(streaming)
                .chatModel(streaming ? mock(ChatModel.class) : null)
                .streamingChatModel(streaming ? null : mock(StreamingChatModel.class)).build();
        var task = Task.builder().provider(provider).build();

        var result = TestMemory.factory(List.of()).forTask(
                task, null, UUID.randomUUID(), mock(TaskExecutionObserver.class));

        var failure = assertInstanceOf(ServiceResult.Failure.class, result);
        assertEquals(ErrorCode.NOT_IMPLEMENTED, failure.code());
        assertEquals("Task provider has no configured model", failure.message());
    }

    @Test
    void streamingClientExecutesToolsAndContinuesTheModelConversation() {
        List<ChatRequest> requests = new ArrayList<>();
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("ping-1").name("ping").arguments("{}").build();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                if (requests.size() == 1) {
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build());
                } else {
                    handler.onPartialResponse("pong");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("pong")).build());
                }
            }
        };
        PingTool tool = new PingTool();
        LlmClient client = value(TestMemory.factory(List.of(tool)).createClientForLoop(streamingSession(model)));
        List<ChatResponse> responses = new ArrayList<>();

        client.chatWithStream(TestMemory.ID, "Use ping").onCompleteResponse(responses::add)
                .onError(error -> fail(error)).start();

        assertEquals(1, tool.calls);
        assertEquals(2, requests.size());
        assertEquals("ping", requests.getFirst().parameters().toolSpecifications().getFirst().name());
        assertTrue(requests.getLast().messages().stream().anyMatch(message ->
                message instanceof ToolExecutionResultMessage result
                        && "ping-1".equals(result.id()) && "pong".equals(result.text())));
        assertEquals("pong", responses.getFirst().aiMessage().text());
    }

    @Test
    void keepsConversationHistoryWithinItsSession() {
        RecordingChatModel model = new RecordingChatModel();
        LlmClientFactory factory = TestMemory.factory(List.of());
        Session first = session(model);
        Session second = session(model);

        LlmClient firstClient = value(factory.createClientForLoop(first));
        assertEquals("[\"reply\"]", TestLlmClients.chat(firstClient, first.getMemoryId(), "First message").content());
        TestLlmClients.chat(firstClient, first.getMemoryId(), "Follow-up message");
        TestLlmClients.chat(value(factory.createClientForLoop(second)), second.getMemoryId(), "Other session");

        assertEquals(3, model.requests.size());
        var firstMessages = model.requests.get(0).messages();
        var followUpMessages = model.requests.get(1).messages();
        assertEquals(1, firstMessages.size());
        assertTrue(((UserMessage) firstMessages.getFirst()).singleText().contains("First message"));
        assertEquals(3, followUpMessages.size());
        // The shared RAG keeps the original user text in memory, without per-request formatting instructions.
        assertEquals(UserMessage.from("First message"), followUpMessages.getFirst());
        assertEquals(AiMessage.from("[\"reply\"]"), followUpMessages.get(1));
        assertTrue(((UserMessage) followUpMessages.getLast()).singleText().contains("Follow-up message"));
        var otherMessages = model.requests.get(2).messages();
        assertEquals(1, otherMessages.size());
        assertTrue(((UserMessage) otherMessages.getFirst()).singleText().contains("Other session"));
    }

    @Test
    void logsTheFullSuccessfulInvocationWithOneInvocationId() {
        LlmClientFactory factory = TestMemory.factory(List.of());
        Session session = session(new RecordingChatModel());

        List<ILoggingEvent> logs = captureListenerLogs(() ->
                assertEquals("[\"reply\"]", TestLlmClients.chat(value(factory.createClientForLoop(session)), TestMemory.ID, "Hello").content()));

        assertEquals(List.of(
                DefaultAiServiceStartedListener.class.getName(),
                DefaultAiServiceRequestIssuedListener.class.getName(),
                DefaultAiServiceResponseReceivedListener.class.getName(),
                DefaultAiServiceCompletedListener.class.getName()),
                logs.stream().map(ILoggingEvent::getLoggerName).toList());
        Object invocationId = logs.getFirst().getArgumentArray()[0];
        assertTrue(invocationId.toString().startsWith("invocationId="));
        for (ILoggingEvent logged : logs) {
            assertEquals(Level.INFO, logged.getLevel());
            assertEquals(invocationId.toString(), logged.getArgumentArray()[0].toString());
            assertTrue(logged.getFormattedMessage().contains("event="));
            assertFalse(logged.getFormattedMessage().contains("methodArguments="));
        }
    }

    @Test
    void logsModelErrorsWithoutLoggingSuccessfulCompletion() {
        RuntimeException error = new IllegalStateException("Model unavailable");
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw error;
            }
        };
        LlmClientFactory factory = TestMemory.factory(List.of());

        List<ILoggingEvent> logs = captureListenerLogs(() -> assertSame(error,
                assertThrows(IllegalStateException.class,
                        () -> TestLlmClients.chat(value(factory.createClientForLoop(session(model))), TestMemory.ID, "Hello"))));

        assertEquals(List.of(
                DefaultAiServiceStartedListener.class.getName(),
                DefaultAiServiceRequestIssuedListener.class.getName(),
                DefaultAiServiceErrorListener.class.getName()),
                logs.stream().map(ILoggingEvent::getLoggerName).toList());
        assertEquals(logs.getFirst().getArgumentArray()[0].toString(), logs.getLast().getArgumentArray()[0].toString());
        assertEquals(Level.INFO, logs.getLast().getLevel());
        assertTrue(logs.getLast().getFormattedMessage().contains("errorType=java.lang.IllegalStateException"));
        assertNull(logs.getLast().getThrowableProxy());
    }

    @Test
    void registersAndExecutesConfiguredTools() {
        List<ChatRequest> requests = new ArrayList<>();
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("ping-1").name("ping").arguments("{}").build();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(requests.size() == 1
                        ? AiMessage.from(toolRequest) : AiMessage.from("[\"pong\"]")).build();
            }
        };
        PingTool tool = new PingTool();
        LlmClientFactory factory = TestMemory.factory(List.of(tool));

        List<ILoggingEvent> logs = captureListenerLogs(Level.DEBUG, () ->
                assertEquals("[\"pong\"]", TestLlmClients.chat(value(factory.createClientForLoop(session(model))), TestMemory.ID, "Use ping").content()));
        assertEquals(1, tool.calls);
        assertEquals(2, requests.size());
        var specifications = requests.getFirst().parameters().toolSpecifications();
        assertEquals(1, specifications.size());
        assertEquals("ping", specifications.getFirst().name());
        var toolResults = requests.getLast().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList();
        assertEquals(1, toolResults.size());
        assertEquals("ping-1", toolResults.getFirst().id());
        assertEquals("ping", toolResults.getFirst().toolName());
        assertEquals("pong", toolResults.getFirst().text());
        var toolLogs = logs.stream()
                .filter(logged -> logged.getLoggerName().equals(DefaultToolExecutedEventListener.class.getName()))
                .filter(logged -> logged.getLevel() == Level.DEBUG)
                .toList();
        assertEquals(1, toolLogs.size());
        assertTrue(toolLogs.getFirst().getFormattedMessage().contains("ping-1"));
        assertTrue(toolLogs.getFirst().getFormattedMessage().contains("pong"));
        assertEquals(logs.getFirst().getArgumentArray()[0].toString(), toolLogs.getFirst().getArgumentArray()[0].toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logsEveryModelExchangeAndToolResultAcrossEightToolRounds(boolean streaming) {
        List<ChatRequest> requests = new ArrayList<>();
        List<ChatResponse> responses = new ArrayList<>();
        java.util.function.Function<ChatRequest, ChatResponse> respond = request -> {
            requests.add(request);
            int round = requests.size();
            var response = ChatResponse.builder().id("response-" + round)
                    .aiMessage(round <= 8 ? AiMessage.from(ToolExecutionRequest.builder()
                            .id("ping-" + round).name("ping").arguments("{}").build())
                            : AiMessage.from("[\"pong\"]"))
                    .build();
            responses.add(response);
            return response;
        };
        ChatModel syncModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return respond.apply(request);
            }
        };
        StreamingChatModel streamModel = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(respond.apply(request));
            }
        };
        var session = streaming ? streamingSession(streamModel) : session(syncModel);
        var parameters = LlmClient.parameters(session, UUID.randomUUID(), streaming ? "WEBSOCKET" : "REST");
        PingTool tool = new PingTool();
        LlmClient client = value(TestMemory.factory(List.of(tool)).createClientForLoop(session));

        List<ILoggingEvent> logs = captureListenerLogs(Level.DEBUG, () -> {
            if (streaming) {
                var completion = new java.util.concurrent.CompletableFuture<ChatResponse>();
                client.chatWithStream(session.getMemoryId(), "Use ping eight times", parameters)
                        .onCompleteResponse(completion::complete).onError(completion::completeExceptionally).start();
                assertEquals("[\"pong\"]", completion.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .join().aiMessage().text());
            } else {
                assertEquals("[\"pong\"]", TestLlmClients.chat(client, session.getMemoryId(),
                        "Use ping eight times", parameters).content());
            }
        });

        assertEquals(8, tool.calls);
        assertEquals(9, requests.size());
        var requestLogs = logs.stream().filter(log -> log.getLevel() == Level.DEBUG
                && log.getMessage().startsWith("event=ai_service_request_issued")
                && log.getLoggerName().equals(DefaultAiServiceRequestIssuedListener.class.getName())).toList();
        var responseLogs = logs.stream().filter(log -> log.getLevel() == Level.DEBUG
                && log.getLoggerName().equals(DefaultAiServiceResponseReceivedListener.class.getName())).toList();
        var toolLogs = logs.stream().filter(log -> log.getLevel() == Level.DEBUG
                && log.getLoggerName().equals(DefaultToolExecutedEventListener.class.getName())).toList();
        assertEquals(9, requestLogs.size());
        assertEquals(9, responseLogs.size());
        assertEquals(8, toolLogs.size());
        for (int round = 0; round < requests.size(); round++) {
            assertTrue(requestLogs.get(round).getFormattedMessage().endsWith("request=" + requests.get(round)));
            assertTrue(responseLogs.get(round).getFormattedMessage().endsWith("response=" + responses.get(round)));
            assertFalse(responseLogs.get(round).getFormattedMessage().contains("request=ChatRequest"));
            if (round < 8) {
                String toolLog = toolLogs.get(round).getFormattedMessage();
                assertTrue(toolLog.contains("toolName=ping, toolCallId=ping-" + (round + 1) + ", arguments={}"));
                assertTrue(toolLog.contains("resultContents="));
                assertTrue(toolLog.contains("pong"));
            }
        }
        String correlation = logs.getFirst().getArgumentArray()[0].toString();
        for (var logged : logs) {
            assertEquals(correlation, logged.getArgumentArray()[0].toString());
            assertTrue(logged.getFormattedMessage().contains("requestId=" + parameters.get(LlmClient.REQUEST_ID)));
            assertFalse(logged.getFormattedMessage().contains("InvocationParameters"));
            assertFalse(logged.getFormattedMessage().contains("methodArguments="));
        }
    }

    private List<ILoggingEvent> captureListenerLogs(Runnable invocation) {
        return captureListenerLogs(Level.INFO, invocation);
    }

    private List<ILoggingEvent> captureListenerLogs(Level level, Runnable invocation) {
        Logger logger = (Logger) LoggerFactory.getLogger(
                "com.mindrails.backend.agent.service.internal.llm.listener");
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(level);
        logger.addAppender(appender);
        try {
            invocation.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }

    private Session session(ChatModel model) {
        return Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(false).chatModel(model).build()).build();
    }

    private Session streamingSession(StreamingChatModel model) {
        return Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(model).build()).build();
    }

    private <T> T value(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    private static class RecordingChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
        }
    }

    private static class RecordingStreamingChatModel implements StreamingChatModel {
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
            handler.onPartialResponse("re");
            handler.onPartialResponse("ply");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("reply")).build());
        }
    }

    public static class PingTool implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();
        int calls;

        @Override
        public UUID id() {
            return id;
        }

        @Tool("Returns pong")
        public String ping() {
            calls++;
            return "pong";
        }
    }
}
