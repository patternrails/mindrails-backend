package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType.*;

import com.mindrails.backend.agent.api.websocket.OutboundMessageSender.Draft;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads;
import com.mindrails.backend.agent.api.websocket.protocol.RunState;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.json.JsonMapper;

class OutboundMessageSenderTest {
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final int originalBatchMillis = AgentWebSocketProperties.batchMillis;
    private final int originalOutboundBytes = AgentWebSocketProperties.outboundBytes;
    private final int originalSendTimeoutMillis = AgentWebSocketProperties.sendTimeoutMillis;
    OutboundMessageSender sender;

    @AfterEach
    void close() {
        if (sender != null) sender.close(CloseStatus.NORMAL);
        scheduler.shutdownNow();
        AgentWebSocketProperties.batchMillis = originalBatchMillis;
        AgentWebSocketProperties.outboundBytes = originalOutboundBytes;
        AgentWebSocketProperties.sendTimeoutMillis = originalSendTimeoutMillis;
    }

    @Test
    void batchesAdjacentTextButFlushesBeforeChannelRoundAndTerminalBoundaries() throws Exception {
        TestSocket socket = new TestSocket();
        AgentWebSocketProperties.batchMillis = 1000; // Terminal event must force an immediate flush.
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        UUID requestId = UUID.randomUUID(), messageId = UUID.randomUUID();
        sender.send(new Draft(ANSWER_DELTA, requestId, new StreamPayloads.TextDelta(1, messageId, "你")));
        sender.send(new Draft(ANSWER_DELTA, requestId, new StreamPayloads.TextDelta(1, messageId, "好")));
        sender.send(new Draft(THINKING_DELTA, requestId, new StreamPayloads.TextDelta(1, messageId, "思考")));
        sender.send(new Draft(ANSWER_DELTA, requestId, new StreamPayloads.TextDelta(2, messageId, "第二轮")));
        sender.send(new Draft(RUN_COMPLETED, requestId, new StreamPayloads.State(RunState.COMPLETED)));
        TestSocket.await(() -> socket.messages.size() == 4);
        assertEquals("你好", socket.messages.getFirst().path("data").path("text").asString());
        assertEquals(java.util.List.of("answer.delta", "thinking.delta", "answer.delta", "run.completed"), socket.types());
        for (int i = 0; i < socket.messages.size(); i++) assertEquals(i + 1, socket.messages.get(i).path("seq").longValue());
    }

    @Test
    void flushesTextOnTheTimerWithoutRequiringACompletionEvent() throws Exception {
        TestSocket socket = new TestSocket();
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        sender.send(new Draft(ANSWER_DELTA, UUID.randomUUID(), new StreamPayloads.TextDelta(1, UUID.randomUUID(), "hello")));
        TestSocket.await(() -> socket.messages.size() == 1);
    }

    @Test
    void chargesInFlightBytesAndClosesWithoutBlockingProducerOnOverflow() throws Exception {
        TestSocket socket = new TestSocket();
        socket.blockedType = "command.error";
        AgentWebSocketProperties.outboundBytes = 800;
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        assertTrue(sender.send(new Draft(COMMAND_ERROR, null, "a".repeat(300))));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        assertTrue(sender.bufferedBytes() > 300);
        assertFalse(sender.send(new Draft(COMMAND_ERROR, null, "b".repeat(300))));
        TestSocket.await(() -> socket.closeStatus != null);
        assertEquals(1013, socket.closeStatus.getCode());
        assertEquals(0, sender.bufferedBytes());
        assertFalse(sender.send(new Draft(COMMAND_ERROR, null, "ignored")));
    }

    @Test
    void timesOutEvenWhenThereIsOnlyOneBlockedSend() throws Exception {
        TestSocket socket = new TestSocket();
        socket.blockedType = "command.error";
        AgentWebSocketProperties.sendTimeoutMillis = 50;
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        sender.send(new Draft(COMMAND_ERROR, null, "blocked"));
        TestSocket.await(() -> socket.closeStatus != null);
        assertEquals(1013, socket.closeStatus.getCode());
        assertEquals(0, sender.bufferedBytes());
    }

    @Test
    void concurrentProducersKeepSequencesInSendOrder() throws Exception {
        TestSocket socket = new TestSocket();
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) {
                int value = i;
                tasks.add(executor.submit(() -> assertTrue(sender.send(new Draft(COMMAND_ERROR, null, value)))));
            }
            for (var task : tasks) task.get(2, TimeUnit.SECONDS);
        }
        TestSocket.await(() -> socket.messages.size() == 100);
        for (int i = 0; i < socket.messages.size(); i++) assertEquals(i + 1, socket.messages.get(i).path("seq").longValue());
        assertEquals(100, socket.messages.stream().map(m -> m.path("data").intValue()).distinct().count());
    }

    @Test
    void batchesToolArgumentFragmentsByToolIdWithinTheRequest() throws Exception {
        TestSocket socket = new TestSocket();
        AgentWebSocketProperties.batchMillis = 1000;
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        UUID requestId = UUID.randomUUID();
        sender.send(new Draft(TOOL_ARGUMENTS_DELTA, requestId, new StreamPayloads.ToolArgumentsDelta(1, "t1", null, "{", com.mindrails.backend.agent.api.websocket.protocol.ToolState.PREPARING)));
        sender.send(new Draft(TOOL_ARGUMENTS_DELTA, requestId, new StreamPayloads.ToolArgumentsDelta(1, "t1", "ls", "}", com.mindrails.backend.agent.api.websocket.protocol.ToolState.PREPARING)));
        sender.send(new Draft(TOOL_ARGUMENTS_DELTA, requestId, new StreamPayloads.ToolArgumentsDelta(1, "t2", "other", "{}", com.mindrails.backend.agent.api.websocket.protocol.ToolState.PREPARING)));
        sender.send(new Draft(RUN_COMPLETED, requestId, new StreamPayloads.State(RunState.COMPLETED)));
        TestSocket.await(() -> socket.messages.size() == 3);
        assertEquals("{}", socket.messages.getFirst().path("data").path("text").asString());
        assertEquals("ls", socket.messages.getFirst().path("data").path("toolName").asString());
        assertEquals("t2", socket.messages.get(1).path("data").path("toolCallId").asString());
    }

    @ParameterizedTest
    @EnumSource(value = ServerMessageType.class, names = {"ANSWER_DELTA", "THINKING_DELTA", "TOOL_ARGUMENTS_DELTA"})
    void neverBatchesFragmentsAcrossClientRequests(ServerMessageType type) throws Exception {
        TestSocket socket = new TestSocket();
        AgentWebSocketProperties.batchMillis = 1000;
        socket.blockedType = "command.error";
        sender = new OutboundMessageSender(socket.session, socket.agentSessionScope, JsonMapper.builder().build(), scheduler);
        sender.send(new Draft(COMMAND_ERROR, null, "hold writer"));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), messageId = UUID.randomUUID();
        Object data = type == TOOL_ARGUMENTS_DELTA
                ? new StreamPayloads.ToolArgumentsDelta(1, "t1", "tool", "fragment", com.mindrails.backend.agent.api.websocket.protocol.ToolState.PREPARING)
                : new StreamPayloads.TextDelta(1, messageId, "fragment");
        sender.send(new Draft(type, first, data));
        sender.send(new Draft(type, second, data));
        sender.send(new Draft(RUN_COMPLETED, second, new StreamPayloads.State(RunState.COMPLETED)));
        socket.release.countDown();
        TestSocket.await(() -> socket.messages.size() == 4);
        assertEquals(first.toString(), socket.messages.get(1).path("requestId").asString());
        assertEquals(second.toString(), socket.messages.get(2).path("requestId").asString());
        assertEquals("fragment", socket.messages.get(1).path("data").path("text").asString());
        assertEquals("fragment", socket.messages.get(2).path("data").path("text").asString());
    }
}
