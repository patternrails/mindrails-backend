package com.mindrails.backend.agent.service.internal.llm.listener;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DefaultAiServiceCompletedListenerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DefaultAiServiceCompletedListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    void newInstanceReturnsANewCompletedListenerEachTime() {
        DefaultAiServiceCompletedListener first = DefaultAiServiceCompletedListener.newInstance();
        DefaultAiServiceCompletedListener second = DefaultAiServiceCompletedListener.newInstance();

        assertInstanceOf(AiServiceCompletedListener.class, first);
        assertNotSame(first, second);
        assertEquals(AiServiceCompletedEvent.class, first.getEventClass());
    }

    @Test
    void logsInvocationContextAtInfoAndResultAtDebugLevel() {
        UUID invocationId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-10-01T08:00:00Z");
        InvocationContext context = InvocationContext.builder()
                .invocationId(invocationId)
                .interfaceName("com.mindrails.backend.agent.model.LlmClient")
                .methodName("chatWithLines")
                .methodArguments(List.of("Hello", 42))
                .chatMemoryId(memoryId)
                .timestamp(timestamp)
                .build();
        AiServiceCompletedEvent event = AiServiceCompletedEvent.builder()
                .invocationContext(context).result(List.of("first line", "second line")).build();

        DefaultAiServiceCompletedListener.newInstance().onEvent(event);

        assertEquals(2, appender.list.size());
        ILoggingEvent summary = appender.list.getFirst();
        assertEquals(Level.INFO, summary.getLevel());
        assertEquals("event=ai_service_completed, invocationId=" + invocationId
                + ", chatMemoryId=" + memoryId, summary.getFormattedMessage());
        assertNull(summary.getThrowableProxy());
        ILoggingEvent logged = appender.list.getLast();
        assertEquals(Level.DEBUG, logged.getLevel());
        assertEquals(DefaultAiServiceCompletedListener.class.getName(), logged.getLoggerName());
        assertEquals("event=ai_service_completed, invocationId=" + invocationId
                + ", chatMemoryId=" + memoryId + ", result=[first line, second line]",
                logged.getFormattedMessage());
    }

    @Test
    void logsNullForAnEmptyResultAndMissingChatMemoryId() {
        InvocationContext context = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("ExampleService")
                .methodName("run")
                .timestamp(Instant.EPOCH)
                .build();
        AiServiceCompletedEvent event = AiServiceCompletedEvent.builder().invocationContext(context).build();

        assertDoesNotThrow(() -> DefaultAiServiceCompletedListener.newInstance().onEvent(event));

        assertEquals(2, appender.list.size());
        ILoggingEvent logged = appender.list.getLast();
        assertEquals(Level.DEBUG, logged.getLevel());
        assertFalse(logged.getFormattedMessage().contains("methodArguments="));
        assertTrue(logged.getFormattedMessage().endsWith("result=null"));
    }

    @Test
    void infoLevelLogsOnlyEventAndCorrelation() {
        logger.setLevel(Level.INFO);
        InvocationContext context = InvocationContext.builder()
                .invocationId(UUID.randomUUID()).interfaceName("ExampleService")
                .methodName("run").timestamp(Instant.EPOCH).build();
        AiServiceCompletedEvent event = AiServiceCompletedEvent.builder()
                .invocationContext(context).result("Detailed result").build();

        DefaultAiServiceCompletedListener.newInstance().onEvent(event);

        assertEquals(1, appender.list.size());
        ILoggingEvent logged = appender.list.getFirst();
        assertEquals(Level.INFO, logged.getLevel());
        assertEquals("event=ai_service_completed, invocationId=" + context.invocationId(),
                logged.getFormattedMessage());
        assertNull(logged.getThrowableProxy());
    }
}
