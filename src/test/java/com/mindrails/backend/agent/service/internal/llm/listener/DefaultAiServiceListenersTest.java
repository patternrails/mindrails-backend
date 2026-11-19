package com.mindrails.backend.agent.service.internal.llm.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import com.mindrails.backend.agent.model.LlmClient;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.CompensationReason;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolCompensatedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class DefaultAiServiceListenersTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final InvocationParameters PARAMETERS = InvocationParameters.from(Map.of(
            LlmClient.REQUEST_ID, REQUEST_ID, LlmClient.RUN_ID, RUN_ID,
            LlmClient.MODEL_NAME, "configured-model", LlmClient.TOOL_SOURCES, "large-internal-tool-catalog"));
    private static final InvocationContext CONTEXT = InvocationContext.builder()
            .invocationId(UUID.randomUUID())
            .interfaceName("ExampleService")
            .methodName("chat")
            .methodArguments(List.of("Hello", PARAMETERS, new Object() {
                @Override public String toString() { throw new AssertionError("Method arguments must not be logged"); }
            }))
            .invocationParameters(PARAMETERS)
            .chatMemoryId(UUID.randomUUID())
            .timestamp(Instant.parse("2026-10-01T08:00:00Z"))
            .build();
    private static final UserMessage USER_MESSAGE = UserMessage.from("Hello");
    private static final SystemMessage SYSTEM_MESSAGE = SystemMessage.from("Be helpful");
    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(SYSTEM_MESSAGE, USER_MESSAGE).build();
    private static final ChatResponse RESPONSE = ChatResponse.builder()
            .aiMessage(AiMessage.from("Answer")).build();
    private static final ToolExecutionRequest TOOL_REQUEST = ToolExecutionRequest.builder()
            .id("tool-1").name("lookup").arguments("{\"query\":\"Hello\"}").build();

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    void newInstanceReturnsANewListenerWithTheCorrectEventType(ListenerCase<?> sample) {
        var first = sample.factory().get();
        var second = sample.factory().get();

        assertNotNull(first);
        assertNotSame(first, second);
        assertEquals(sample.event().eventClass(), first.getEventClass());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    void logsInvocationContextAndEventDetailsAtDebugLevel(ListenerCase<?> sample) {
        ILoggingEvent logged = capture(sample.factory().get().getClass(), sample::emit);

        assertEquals(Level.DEBUG, logged.getLevel());
        String message = logged.getFormattedMessage();
        assertTrue(message.startsWith("event=" + sample.label() + ", invocationId=" + CONTEXT.invocationId()));
        assertCorrelation(message);
        assertNoInvocationDump(message);
        for (String field : sample.expectedFields()) {
            assertTrue(message.contains(field), () -> "Missing field " + field + " in " + message);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    void infoLevelLogsCompactEventSummaries(ListenerCase<?> sample) {
        List<ILoggingEvent> logs = capture(sample.factory().get().getClass(), Level.INFO, sample::emit);

        assertEquals(1, logs.size());
        ILoggingEvent logged = logs.getFirst();
        assertEquals(Level.INFO, logged.getLevel());
        String message = logged.getFormattedMessage();
        assertTrue(message.startsWith("event=" + sample.label() + ", invocationId=" + CONTEXT.invocationId()));
        assertCorrelation(message);
        assertNoInvocationDump(message);
        assertFalse(message.contains("Be helpful"));
        assertFalse(message.contains("Answer"));
        assertFalse(message.contains("Tool result"));
        assertFalse(message.contains("arguments="));
        assertFalse(message.contains("request=ChatRequest"));
        assertFalse(message.contains("response=ChatResponse"));
        assertNull(logged.getThrowableProxy());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    void handlesMissingChatMemoryIdAndEmptyMethodArguments(ListenerCase<?> sample) {
        InvocationContext context = InvocationContext.builder()
                .invocationId(UUID.randomUUID()).interfaceName("ExampleService")
                .methodName("chat").timestamp(Instant.EPOCH).build();

        ILoggingEvent logged = capture(sample.factory().get().getClass(), () -> sample.emit(context));

        assertTrue(logged.getFormattedMessage().contains("invocationId=" + context.invocationId()));
        assertFalse(logged.getFormattedMessage().contains("chatMemoryId="));
        assertNoInvocationDump(logged.getFormattedMessage());
    }

    @Test
    void startedListenerHandlesAnAbsentSystemMessage() {
        AiServiceStartedEvent event = AiServiceStartedEvent.builder()
                .invocationContext(CONTEXT).userMessage(USER_MESSAGE).build();

        ILoggingEvent logged = capture(DefaultAiServiceStartedListener.class,
                () -> DefaultAiServiceStartedListener.newInstance().onEvent(event));

        assertTrue(logged.getFormattedMessage().contains("systemMessage=null, userMessage=" + USER_MESSAGE));
    }

    @Test
    void errorListenerLogsTheExceptionAndItsCause() {
        Throwable cause = new IllegalStateException("Connection closed");
        Throwable error = new RuntimeException("Model request failed", cause);
        AiServiceErrorEvent event = AiServiceErrorEvent.builder()
                .invocationContext(CONTEXT).error(error).build();

        ILoggingEvent logged = capture(DefaultAiServiceErrorListener.class,
                () -> DefaultAiServiceErrorListener.newInstance().onEvent(event));

        assertTrue(logged.getFormattedMessage().endsWith("error=" + error));
        assertNotNull(logged.getThrowableProxy());
        assertEquals(error.getClass().getName(), logged.getThrowableProxy().getClassName());
        assertEquals(error.getMessage(), logged.getThrowableProxy().getMessage());
        assertEquals(cause.getMessage(), logged.getThrowableProxy().getCause().getMessage());
    }

    @Test
    void toolExecutedListenerLogsMultipleResultContents() {
        var contents = List.of(TextContent.from("First part"), TextContent.from("Second part"));
        ToolExecutedEvent event = ToolExecutedEvent.builder().invocationContext(CONTEXT)
                .request(TOOL_REQUEST).resultContents(List.copyOf(contents)).build();

        ILoggingEvent logged = capture(DefaultToolExecutedEventListener.class,
                () -> DefaultToolExecutedEventListener.newInstance().onEvent(event));

        assertTrue(logged.getFormattedMessage().contains("resultContents=" + contents));
    }

    @Test
    void toolCompensatedListenerLogsMultipleResultContentsAndCancellationReason() {
        var contents = List.of(TextContent.from("First part"), TextContent.from("Second part"));
        ToolCompensatedEvent event = ToolCompensatedEvent.builder().invocationContext(CONTEXT)
                .request(TOOL_REQUEST).resultContents(List.copyOf(contents))
                .reason(CompensationReason.INVOCATION_CANCELLED).build();

        ILoggingEvent logged = capture(DefaultToolCompensatedEventListener.class,
                () -> DefaultToolCompensatedEventListener.newInstance().onEvent(event));

        assertTrue(logged.getFormattedMessage().contains("resultContents=" + contents));
        assertTrue(logged.getFormattedMessage().endsWith("reason=INVOCATION_CANCELLED"));
    }

    @Test
    void toolSummaryIdentifiesTheCallWithoutIncludingPayloads() {
        var event = ToolExecutedEvent.builder().invocationContext(CONTEXT)
                .request(TOOL_REQUEST).resultText("Tool result").build();
        var logged = capture(DefaultToolExecutedEventListener.class, Level.INFO,
                () -> DefaultToolExecutedEventListener.newInstance().onEvent(event)).getFirst();

        assertTrue(logged.getFormattedMessage().endsWith(
                "toolName=lookup, toolCallId=tool-1, resultContentCount=1"));
    }

    @Test
    void modelSummaryFallsBackToConfiguredModelAndPrefersReportedModel() {
        for (String reportedModel : new String[] {null, "reported-model"}) {
            var event = AiServiceRequestIssuedEvent.builder().invocationContext(CONTEXT)
                    .request(REQUEST.toBuilder().modelName(reportedModel).build()).build();
            var logged = capture(DefaultAiServiceRequestIssuedListener.class, Level.INFO,
                    () -> DefaultAiServiceRequestIssuedListener.newInstance().onEvent(event)).getFirst();

            assertTrue(logged.getFormattedMessage().contains("modelName="
                    + (reportedModel == null ? "configured-model" : reportedModel)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logsTheActualSystemMessageAtDebugEvenWhenTheRequestContainsAnImage(boolean includeImage) {
        var systemMessage = SystemMessage.from("Skill instructions\nUse the session directory.");
        var userMessage = includeImage
                ? UserMessage.from(TextContent.from("User text"), ImageContent.from("AA==", "image/png"))
                : UserMessage.from("User text");
        var request = ChatRequest.builder().messages(systemMessage, userMessage).build();
        var event = AiServiceRequestIssuedEvent.builder().invocationContext(CONTEXT).request(request).build();

        var log = capture(DefaultAiServiceRequestIssuedListener.class, Level.DEBUG,
                () -> DefaultAiServiceRequestIssuedListener.newInstance().onEvent(event));
        var prompt = log.stream().filter(entry -> entry.getFormattedMessage().startsWith("event=ai_service_system_message"))
                .toList();
        assertEquals(1, prompt.size());
        assertEquals(Level.DEBUG, prompt.getFirst().getLevel());
        String message = prompt.getFirst().getFormattedMessage();
        assertCorrelation(message);
        assertTrue(message.endsWith("systemMessage=" + systemMessage.text()));
        assertFalse(message.contains("User text"));
        assertEquals(!includeImage, log.stream().anyMatch(entry -> entry.getFormattedMessage().contains("request=ChatRequest")));
    }

    @Test
    void cancelledInvocationDoesNotDispatchOrLogAModelRequest() {
        var cancellation = new java.util.concurrent.CompletableFuture<Void>();
        cancellation.complete(null);
        var context = CONTEXT.toBuilder().invocationParameters(
                InvocationParameters.from(Map.of(LlmClient.CANCELLATION, cancellation))).build();
        var event = AiServiceRequestIssuedEvent.builder().invocationContext(context).request(REQUEST).build();

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> DefaultAiServiceRequestIssuedListener.newInstance().onEvent(event));
    }

    private static void assertCorrelation(String message) {
        assertTrue(message.contains("requestId=" + REQUEST_ID));
        assertTrue(message.contains("runId=" + RUN_ID));
        assertTrue(message.contains("chatMemoryId=" + CONTEXT.chatMemoryId()));
    }

    private static void assertNoInvocationDump(String message) {
        assertFalse(message.contains("methodArguments="));
        assertFalse(message.contains("InvocationParameters"));
        assertFalse(message.contains("large-internal-tool-catalog"));
        assertFalse(message.contains("timestamp="));
    }

    private static Stream<ListenerCase<?>> listeners() {
        var commonParams = GuardrailRequestParams.builder()
                .userMessageTemplate("{{message}}")
                .variables(Map.of("message", "Hello")).invocationContext(CONTEXT).build();
        var inputRequest = InputGuardrailRequest.builder()
                .userMessage(USER_MESSAGE).commonParams(commonParams).build();
        var inputResult = InputGuardrailResult.successWith("Rewritten input");
        var outputRequest = OutputGuardrailRequest.builder().responseFromLLM(RESPONSE)
                .requestParams(commonParams).chatExecutor(mock(ChatExecutor.class)).build();
        var outputResult = OutputGuardrailResult.successWith("Validated output");
        Duration duration = Duration.ofMillis(25);
        Throwable error = new IllegalStateException("Model unavailable");

        return Stream.of(
                sample(DefaultAiServiceStartedListener::newInstance,
                        AiServiceStartedEvent.builder().invocationContext(CONTEXT)
                                .systemMessage(SYSTEM_MESSAGE).userMessage(USER_MESSAGE).build(),
                        "ai_service_started", "systemMessage=" + SYSTEM_MESSAGE, "userMessage=" + USER_MESSAGE),
                sample(DefaultAiServiceRequestIssuedListener::newInstance,
                        AiServiceRequestIssuedEvent.builder().invocationContext(CONTEXT).request(REQUEST).build(),
                        "ai_service_request_issued", "request=" + REQUEST),
                sample(DefaultAiServiceResponseReceivedListener::newInstance,
                        AiServiceResponseReceivedEvent.builder().invocationContext(CONTEXT)
                                .request(REQUEST).response(RESPONSE).build(),
                        "ai_service_response_received", "response=" + RESPONSE),
                sample(DefaultAiServiceErrorListener::newInstance,
                        AiServiceErrorEvent.builder().invocationContext(CONTEXT).error(error).build(),
                        "ai_service_error", "error=" + error),
                sample(DefaultToolExecutedEventListener::newInstance,
                        ToolExecutedEvent.builder().invocationContext(CONTEXT)
                                .request(TOOL_REQUEST).resultText("Tool result").build(),
                        "tool_executed", "toolName=lookup", "toolCallId=tool-1", "arguments=" + TOOL_REQUEST.arguments(),
                        "resultContents=" + List.of(TextContent.from("Tool result"))),
                sample(DefaultToolCompensatedEventListener::newInstance,
                        ToolCompensatedEvent.builder().invocationContext(CONTEXT).request(TOOL_REQUEST)
                                .resultText("Tool result").reason(CompensationReason.TOOL_EXECUTION_FAILED).build(),
                        "tool_compensated", "toolName=lookup", "toolCallId=tool-1", "arguments=" + TOOL_REQUEST.arguments(),
                        "resultContents=" + List.of(TextContent.from("Tool result")), "reason=TOOL_EXECUTION_FAILED"),
                sample(DefaultInputGuardrailExecutedListener::newInstance,
                        InputGuardrailExecutedEvent.builder().invocationContext(CONTEXT)
                                .guardrailClass(InputGuardrail.class).guardrailName("input-check")
                                .request(inputRequest).result(inputResult).duration(duration).build(),
                        "input_guardrail_executed", "guardrailClass=" + InputGuardrail.class,
                        "guardrailName=input-check", "userMessage=" + USER_MESSAGE,
                        "result=" + inputResult, "duration=" + duration,
                        "rewrittenUserMessage=" + UserMessage.from("Rewritten input")),
                sample(DefaultOutputGuardrailExecutedListener::newInstance,
                        OutputGuardrailExecutedEvent.builder().invocationContext(CONTEXT)
                                .guardrailClass(OutputGuardrail.class).guardrailName("output-check")
                                .request(outputRequest).result(outputResult).duration(duration).build(),
                        "output_guardrail_executed", "guardrailClass=" + OutputGuardrail.class,
                        "guardrailName=output-check", "responseFromLLM=" + RESPONSE,
                        "result=" + outputResult, "duration=" + duration));
    }

    private static <E extends AiServiceEvent> ListenerCase<E> sample(
            Supplier<? extends AiServiceListener<E>> factory, E event, String label, String... expectedFields) {
        return new ListenerCase<>(factory, event, label, List.of(expectedFields));
    }

    private ILoggingEvent capture(Class<?> type, Runnable emit) {
        return capture(type, Level.DEBUG, emit).getLast();
    }

    private List<ILoggingEvent> capture(Class<?> type, Level level, Runnable emit) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(level);
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(emit::run);
            assertEquals(Level.INFO, appender.list.getFirst().getLevel());
            if (level == Level.DEBUG) {
                assertFalse(appender.list.subList(1, appender.list.size()).isEmpty());
                assertTrue(appender.list.subList(1, appender.list.size()).stream()
                        .allMatch(entry -> entry.getLevel() == Level.DEBUG));
            } else {
                assertEquals(1, appender.list.size());
            }
            for (ILoggingEvent logged : appender.list) {
                assertEquals(type.getName(), logged.getLoggerName());
            }
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }
    }

    private record ListenerCase<E extends AiServiceEvent>(
            Supplier<? extends AiServiceListener<E>> factory, E event, String label, List<String> expectedFields) {

        void emit() {
            factory.get().onEvent(event);
        }

        void emit(InvocationContext context) {
            factory.get().onEvent(event.<E>toBuilder().invocationContext(context).build());
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
