package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.CancelReason;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.json.JsonMapper;

class AgentSessionWebSocketHandlerTest {
    final AgentSessionChatStreamGateway gateway = mock(AgentSessionChatStreamGateway.class);
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final int originalPingMillis = AgentWebSocketProperties.pingMillis;
    private final int originalPongTimeoutMillis = AgentWebSocketProperties.pongTimeoutMillis;
    private final int originalOutboundBytes = AgentWebSocketProperties.outboundBytes;
    private final int originalBatchMillis = AgentWebSocketProperties.batchMillis;
    final AtomicReference<AgentSessionChatStreamGateway.EventSink> sink = new AtomicReference<>();
    final Map<Object, AgentSessionChatStreamGateway.EventSink> boundSinks = new java.util.concurrent.ConcurrentHashMap<>();
    UUID chatRequestId;
    AgentSessionWebSocketHandler handler;

    void initialize() {
        doAnswer(call -> {
            AgentSessionChatStreamGateway.EventSink callback = call.getArgument(2);
            boundSinks.put(call.getArgument(0), callback);
            sink.set(callback);
            return ServiceResult.success();
        }).when(gateway).command(any(), eq(AgentSessionChatStreamGateway.CommandType.BIND), any());
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.UNBIND), any())).thenReturn(ServiceResult.success());
        when(gateway.validate(any())).thenReturn(ServiceResult.success(new AgentSessionChatStreamGateway.SessionSnapshot(null)));
        doAnswer(invocation -> {
            ClientCommand.Chat command = invocation.getArgument(1);
            chatRequestId = command.requestId();
            sink.set(boundSinks.get(invocation.getArgument(0)));
            sink.get().emit(new ChatStreamEvent.Started(chatRequestId));
            return ServiceResult.success();
        }).when(gateway).chat(any(), any());
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.success());
        handler = new AgentSessionWebSocketHandler(gateway, JsonMapper.builder().build(), scheduler);
    }

    @AfterEach
    void close() {
        if (handler != null) handler.shutdown();
        scheduler.shutdownNow();
        AgentWebSocketProperties.pingMillis = originalPingMillis;
        AgentWebSocketProperties.pongTimeoutMillis = originalPongTimeoutMillis;
        AgentWebSocketProperties.outboundBytes = originalOutboundBytes;
        AgentWebSocketProperties.batchMillis = originalBatchMillis;
    }

    @Test
    void connectionBindsBeforeChatAndUnbindsTheSameSinkExactlyOnceWithoutStopping() throws Exception {
        initialize();
        var socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        TestSocket.await(() -> socket.types().contains("connection.ready"));
        var bound = sink.get();
        var requestId = UUID.randomUUID();
        bound.emit(new ChatStreamEvent.Started(requestId));
        TestSocket.await(() -> socket.types().contains("run.started"));
        verify(gateway, never()).chat(any(), any());
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        handler.handleTransportError(socket.session, new IllegalStateException("Already closed"));
        handler.shutdown();
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.BIND, bound);
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.UNBIND, bound);
        verify(gateway, never()).command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), any());
        assertEquals(List.of("connection.ready", "run.started"), socket.types());
    }

    @Test
    void synchronousEventsAreForwardedAndEveryChatIsDelegated() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        TestSocket.await(() -> socket.types().contains("connection.ready"));
        socket.blockedType = "chat.accepted";
        UUID requestId = UUID.randomUUID();
        send(socket, chat(requestId, "hello"));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        assertTrue(socket.types().contains("run.started"));
        socket.release.countDown();
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().stream().filter("chat.accepted"::equals).count() == 2);
        send(socket, chat(requestId, "changed"));
        TestSocket.await(() -> socket.types().stream().filter("chat.accepted"::equals).count() == 3);
        assertEquals(3, socket.types().stream().filter("run.started"::equals).count());
        assertFalse(socket.types().contains("command.error"));
        verify(gateway, times(2)).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(requestId, "hello")));
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(requestId, "changed")));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void validationFailuresRejectChatWithoutClosingTheConnection(ErrorCode code) throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        doReturn(ServiceResult.failure(code, "Cannot use Session")).when(gateway).chat(eq(socket.agentSessionScope), any());
        handler.afterConnectionEstablished(socket.session);
        TestSocket.await(() -> socket.types().contains("connection.ready"));
        UUID requestId = UUID.randomUUID();
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().contains("command.error"));
        assertEquals(code.name(), socket.last("command.error").path("data").path("code").asString());
        assertEquals(requestId.toString(), socket.last("command.error").path("requestId").asString());
        assertTrue(socket.open.get());
        assertFalse(socket.types().contains("run.started"));
        doReturn(ServiceResult.success()).when(gateway).chat(eq(socket.agentSessionScope), any());
        send(socket, chat(UUID.randomUUID(), "retry"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        verify(gateway, times(2)).chat(eq(socket.agentSessionScope), any());
    }


    @Test
    void defaultGatewayAllowsConnectionAndRejectsChatAsNotImplemented() throws Exception {
        var fallback = spy(new AgentSessionWebSocketConfiguration().unavailableAgentChatStreamGateway());
        handler = new AgentSessionWebSocketHandler(fallback, JsonMapper.builder().build(), scheduler);
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        TestSocket.await(() -> socket.closeStatus != null);
        assertEquals("NOT_IMPLEMENTED", socket.last("command.error").path("data").path("code").asString());
        assertFalse(socket.types().contains("connection.ready"));
        verify(fallback, never()).chat(any(), any());
    }


    @Test
    void duplicateChatReturnsTheLatestGatewayFailure() throws Exception {
        initialize();
        doReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"),
                ServiceResult.failure(ErrorCode.CONFLICT, "Session busy"))
                .when(gateway).chat(any(), any());
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().stream().filter("command.error"::equals).count() == 1);
        var first = socket.last("command.error");
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().stream().filter("command.error"::equals).count() == 2);
        var second = socket.last("command.error");

        assertEquals("NOT_FOUND", first.path("data").path("code").asString());
        assertEquals("CONFLICT", second.path("data").path("code").asString());
        assertEquals("Session busy", second.path("data").path("message").asString());
        assertEquals(requestId.toString(), second.path("requestId").asString());
        assertTrue(second.path("seq").longValue() > first.path("seq").longValue());
        verify(gateway, times(2)).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(requestId, "hello")));
        assertFalse(socket.types().contains("run.started"));
    }

    @Test
    void changedMessageWithTheSameRequestIdIsDelegated() throws Exception {
        initialize();
        doReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"),
                ServiceResult.success(), ServiceResult.success())
                .when(gateway).chat(any(), any());
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().contains("command.error"));
        send(socket, chat(requestId, "changed"));
        TestSocket.await(() -> socket.types().stream().filter("chat.accepted"::equals).count() == 1);
        send(socket, chat(requestId, "hello"));
        TestSocket.await(() -> socket.types().stream().filter("chat.accepted"::equals).count() == 2);

        assertEquals(1, socket.types().stream().filter("command.error"::equals).count());
        assertEquals("NOT_FOUND", socket.last("command.error").path("data").path("code").asString());
        assertEquals(requestId.toString(), socket.last("chat.accepted").path("requestId").asString());
        verify(gateway, times(2)).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(requestId, "hello")));
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(requestId, "changed")));
    }

    @Test
    void forwardsMultipleRoundsAndToolsFromTheGateway() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID();
        var events = List.<ChatStreamEvent>of(new ChatStreamEvent.ThinkingDelta(chatRequestId, 1, m1, "plan"),
                new ChatStreamEvent.ToolArgumentsDelta(chatRequestId, 1, "t1", "ls", "{}"),
                new ChatStreamEvent.ModelResponseCompleted(chatRequestId, 1, m1, "TOOL_EXECUTION", List.of(new ChatStreamEvent.ToolCall("t1", "ls", Map.of()))),
                new ChatStreamEvent.ToolStarted(chatRequestId, 1, "t1", "ls", Map.of()),
                new ChatStreamEvent.ToolCompleted(chatRequestId, 1, "t1", "ls", "files", 10L),
                new ChatStreamEvent.AnswerDelta(chatRequestId, 2, m2, "done"),
                new ChatStreamEvent.Completed(chatRequestId, m2, "done", "STOP", new ChatStreamEvent.Usage(10L, 20L, 30L)));
        events.forEach(e -> sink.get().emit(e));
        TestSocket.await(() -> socket.types().contains("run.completed"));
        assertEquals(List.of("connection.ready", "run.started", "chat.accepted", "thinking.delta", "tool.arguments.delta",
                "model.response.completed", "tool.started", "tool.completed", "answer.delta", "run.completed"), socket.types());
        assertEquals("done", socket.last("run.completed").path("data").path("answer").asString());
        assertEquals(30, socket.last("run.completed").path("data").path("usage").path("totalTokens").intValue());
        for (int i = 0; i < socket.messages.size(); i++) assertEquals(i + 1, socket.messages.get(i).path("seq").longValue());
    }

    @Test
    void batchesMappedAnswerEventsAndPreservesTheClientRequestId() throws Exception {
        initialize();
        AgentWebSocketProperties.batchMillis = 1000;
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        socket.blockedType = "chat.accepted";
        UUID requestId = UUID.randomUUID(), messageId = UUID.randomUUID();
        send(socket, chat(requestId, "work"));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        sink.get().emit(new ChatStreamEvent.AnswerDelta(chatRequestId, 1, messageId, "你"));
        sink.get().emit(new ChatStreamEvent.AnswerDelta(chatRequestId, 1, messageId, "好"));
        sink.get().emit(new ChatStreamEvent.Completed(chatRequestId, messageId, "你好", "STOP", null));
        socket.release.countDown();
        TestSocket.await(() -> socket.types().contains("run.completed"));

        assertEquals(1, socket.types().stream().filter("answer.delta"::equals).count());
        assertEquals("你好", socket.last("answer.delta").path("data").path("text").asString());
        for (var frame : socket.messages) {
            assertFalse(frame.has("runId"));
            if (!frame.path("type").asString().equals("connection.ready"))
                assertEquals(requestId.toString(), frame.path("requestId").asString());
        }
    }

    @Test
    void forwardsTheRequestIdCarriedByTheEventWithoutReplacingIt() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID commandId = UUID.randomUUID(), eventRequestId = UUID.randomUUID(), messageId = UUID.randomUUID();
        send(socket, chat(commandId, "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        sink.get().emit(new ChatStreamEvent.AnswerDelta(eventRequestId, 1, messageId, "answer"));
        sink.get().emit(new ChatStreamEvent.Completed(eventRequestId, messageId, "answer", "STOP", null));
        TestSocket.await(() -> socket.types().contains("run.completed"));

        assertEquals(commandId.toString(), socket.last("chat.accepted").path("requestId").asString());
        assertEquals(eventRequestId.toString(), socket.last("answer.delta").path("requestId").asString());
        assertEquals(eventRequestId.toString(), socket.last("run.completed").path("requestId").asString());
        assertFalse(socket.last("answer.delta").path("data").has("requestId"));
    }

    @Test
    void gatewayDecidesWhetherAnotherChatCanStart() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID firstRequest = UUID.randomUUID();
        send(socket, chat(firstRequest, "first"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        doReturn(ServiceResult.failure(ErrorCode.CONFLICT, "Session already has an active run")).when(gateway).chat(any(), any());
        UUID secondRequest = UUID.randomUUID();
        send(socket, chat(secondRequest, "second"));
        TestSocket.await(() -> socket.types().contains("command.error"));
        assertEquals("CONFLICT", socket.last("command.error").path("data").path("code").asString());
        assertEquals(secondRequest.toString(), socket.last("command.error").path("requestId").asString());
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(firstRequest, "first")));
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(secondRequest, "second")));
    }

    @Test
    void cancellationIsDelegatedWhileAcceptanceWriteIsBlocked() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        socket.blockedType = "chat.accepted";
        UUID chatId = UUID.randomUUID();
        send(socket, chat(chatId, "work"));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        UUID cancelId = UUID.randomUUID();
        send(socket, cancel(cancelId));
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
        sink.get().emit(new ChatStreamEvent.Cancelled(chatId, CancelReason.USER_CANCELLED));
        socket.release.countDown();
        TestSocket.await(() -> socket.types().contains("run.cancelled"));
        assertEquals(cancelId.toString(), socket.last("run.cancelling").path("requestId").asString());
        assertEquals("CANCELLING", socket.last("run.cancelling").path("data").path("state").asString());
        assertEquals(chatId.toString(), socket.last("run.cancelled").path("requestId").asString());
        assertEquals("CANCELLED", socket.last("run.cancelled").path("data").path("state").asString());
        assertTrue(socket.last("run.cancelled").path("seq").longValue()
                > socket.last("run.cancelling").path("seq").longValue());
    }

    @Test
    void cancellationFailureLeavesTheStreamUsable() throws Exception {
        initialize();
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED,
                "Session stream commands are not implemented"));
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        send(socket, cancel(UUID.randomUUID()));
        TestSocket.await(() -> socket.types().contains("command.error"));
        assertEquals("NOT_IMPLEMENTED", socket.last("command.error").path("data").path("code").asString());
        UUID message = UUID.randomUUID();
        sink.get().emit(new ChatStreamEvent.AnswerDelta(chatRequestId, 1, message, "answer"));
        sink.get().emit(new ChatStreamEvent.Completed(chatRequestId, message, "answer", "STOP", null));
        TestSocket.await(() -> socket.types().contains("run.completed"));
        assertTrue(socket.types().contains("answer.delta"));
        assertFalse(socket.types().contains("run.cancelled"));
        assertTrue(socket.types().contains("run.cancelling"));
        assertTrue(socket.last("command.error").path("seq").longValue()
                > socket.last("run.cancelling").path("seq").longValue());
    }

    @Test
    void malformedMessagesAndBusinessErrorsLeaveTheConnectionUsable() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, "bad JSON");
        doReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found")).when(gateway).chat(any(), any());
        send(socket, chat(UUID.randomUUID(), "hello"));
        TestSocket.await(() -> socket.types().stream().filter("command.error"::equals).count() == 2);
        assertTrue(socket.open.get());
        assertEquals("NOT_FOUND", socket.last("command.error").path("data").path("code").asString());
        assertFalse(socket.types().contains("run.started"));
    }

    @Test
    void dispatchExceptionPropagatesAndTransportErrorClosesTheConnection() throws Exception {
        initialize();
        doThrow(new IllegalStateException("Cannot reserve run")).when(gateway).chat(any(), any());
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        var exception = assertThrows(IllegalStateException.class,
                () -> send(socket, chat(requestId, "hello")));

        assertEquals("Cannot reserve run", exception.getMessage());
        assertFalse(socket.types().contains("command.error"));
        assertFalse(socket.types().contains("run.started"));

        handler.handleTransportError(socket.session, exception);
        TestSocket.await(() -> socket.closeStatus != null);
        assertEquals(CloseStatus.SERVER_ERROR.getCode(), socket.closeStatus.getCode());
        send(socket, chat(UUID.randomUUID(), "after close"));
        verify(gateway, times(1)).chat(any(), any());
    }

    @Test
    void disconnectSuppressesEventsWhenAcceptanceWriteHasNotCompleted() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        socket.blockedType = "chat.accepted";
        send(socket, chat(UUID.randomUUID(), "work"));
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS));
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        sink.get().emit(new ChatStreamEvent.AnswerDelta(chatRequestId, 1, UUID.randomUUID(), "late"));
        assertFalse(socket.types().contains("answer.delta"));
        assertFalse(socket.types().contains("chat.accepted"));
    }

    @Test
    void cancellationFromAnotherConnectionIsScopedByTheGateway() throws Exception {
        initialize();
        TestSocket owner = new TestSocket(), peer = new TestSocket();
        var ownerAttributes = owner.session.getAttributes();
        when(peer.session.getAttributes()).thenReturn(ownerAttributes);
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Run not found"));
        handler.afterConnectionEstablished(owner.session);
        handler.afterConnectionEstablished(peer.session);
        UUID chatId = UUID.randomUUID();
        send(owner, chat(chatId, "work"));
        TestSocket.await(() -> owner.types().contains("chat.accepted"));
        UUID cancelId = UUID.randomUUID();
        send(peer, cancel(cancelId));
        TestSocket.await(() -> peer.types().contains("command.error"));
        assertEquals("NOT_FOUND", peer.last("command.error").path("data").path("code").asString());
        verify(gateway).command(owner.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
        assertFalse(owner.types().contains("run.cancelling"));
        assertTrue(peer.types().contains("run.cancelling"));
        assertFalse(peer.types().contains("run.cancelled"));
    }

    @Test
    void multipleChatsAndCancelAreIndependentlyDelegated() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID(), cancelId = UUID.randomUUID();
        send(socket, chat(firstId, "work"));
        send(socket, chat(secondId, "second"));
        send(socket, cancel(cancelId));
        TestSocket.await(() -> socket.types().contains("run.cancelling"));

        assertEquals(2, socket.types().stream().filter("chat.accepted"::equals).count());
        assertFalse(socket.types().contains("command.error"));
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(firstId, "work")));
        verify(gateway).chat(eq(socket.agentSessionScope), eq(new ClientCommand.Chat(secondId, "second")));
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
    }

    @Test
    void pingPongTimeoutAndRejectedBinaryFramesCleanUpConnections() throws Exception {
        AgentWebSocketProperties.pingMillis = 30;
        AgentWebSocketProperties.pongTimeoutMillis = 60;
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        TestSocket.await(() -> !socket.pings.isEmpty());
        handler.handleMessage(socket.session, new PongMessage(socket.pings.getFirst().getPayload()));
        TestSocket.await(() -> socket.pings.size() >= 2);
        assertTrue(socket.open.get());
        TestSocket.await(() -> socket.closeStatus != null);
        assertEquals(1001, socket.closeStatus.getCode());
        TestSocket binary = new TestSocket();
        handler.afterConnectionEstablished(binary.session);
        handler.handleMessage(binary.session, new BinaryMessage(new byte[]{1}));
        TestSocket.await(() -> binary.closeStatus != null);
        assertEquals(1003, binary.closeStatus.getCode());
    }

    @Test
    void synchronousStreamFailureIsForwardedBeforeAcceptance() throws Exception {
        initialize();
        doAnswer(invocation -> {
            ClientCommand.Chat command = invocation.getArgument(1);
            AgentSessionChatStreamGateway.EventSink callback = boundSinks.get(invocation.getArgument(0));
            callback.emit(new ChatStreamEvent.Failed(command.requestId(), new ChatStreamEvent.Error(ErrorCode.INTERNAL_ERROR,
                    "Session stream failed")));
            return ServiceResult.success();
        }).when(gateway).chat(any(), any());
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        assertEquals(List.of("connection.ready", "run.failed", "chat.accepted"), socket.types());
        assertEquals("Session stream failed", socket.last("run.failed").path("data").path("error").path("message").asString());
        assertTrue(socket.open.get());
    }

    @Test
    void oversizeFinalAnswerIsDroppedAndConnectionRemainsUsable() throws Exception {
        AgentWebSocketProperties.outboundBytes = 1500;
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        sink.get().emit(new ChatStreamEvent.Completed(chatRequestId, UUID.randomUUID(), "x".repeat(2000), "STOP", null));
        sink.get().emit(new ChatStreamEvent.Completed(chatRequestId, UUID.randomUUID(), "fits", "STOP", null));
        TestSocket.await(() -> socket.types().contains("run.completed"));
        assertEquals(1, socket.types().stream().filter("run.completed"::equals).count());
        assertEquals("fits", socket.last("run.completed").path("data").path("answer").asString());
        assertTrue(socket.open.get());
    }

    @Test
    void inboundLimitAndTransportErrorsCleanUpConnections() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        handler.handleTransportError(socket.session, new java.io.IOException("disconnected"));
        sink.get().emit(new ChatStreamEvent.AnswerDelta(chatRequestId, 1, UUID.randomUUID(), "late"));
        assertFalse(socket.types().contains("answer.delta"));
        TestSocket.await(() -> socket.closeStatus != null);
        TestSocket oversized = new TestSocket();
        handler.afterConnectionEstablished(oversized.session);
        send(oversized, "你".repeat(AgentWebSocketProperties.inboundBytes));
        TestSocket.await(() -> oversized.closeStatus != null);
        assertEquals(1009, oversized.closeStatus.getCode());
    }

    @Test
    void validatesCancellationScopeBeforeCallingTheGateway() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        when(gateway.command(eq(socket.agentSessionScope), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull()))
                .thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"));
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, cancel(requestId));
        TestSocket.await(() -> socket.types().contains("command.error"));
        assertEquals("NOT_FOUND", socket.last("command.error").path("data").path("code").asString());
        assertEquals(requestId.toString(), socket.last("command.error").path("requestId").asString());
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
    }


    @Test
    void gatewayConflictAfterCompletionSendsCancellingThenError() throws Exception {
        initialize();
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.failure(ErrorCode.CONFLICT, "Run has ended"));
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, chat(UUID.randomUUID(), "work"));
        TestSocket.await(() -> socket.types().contains("chat.accepted"));
        sink.get().emit(new ChatStreamEvent.Completed(chatRequestId, UUID.randomUUID(), "finished", "STOP", null));
        send(socket, cancel(UUID.randomUUID()));
        TestSocket.await(() -> socket.types().contains("command.error"));
        assertTrue(socket.types().contains("run.completed"));
        assertTrue(socket.types().contains("run.cancelling"));
        assertFalse(socket.types().contains("run.cancelled"));
        assertTrue(socket.last("command.error").path("seq").longValue()
                > socket.last("run.cancelling").path("seq").longValue());
        assertEquals("CONFLICT", socket.last("command.error").path("data").path("code").asString());
    }

    @Test
    void cancellationWithoutALocalChatStillCallsTheGateway() throws Exception {
        initialize();
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Run not found"));
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        send(socket, cancel(UUID.randomUUID()));

        TestSocket.await(() -> socket.types().contains("command.error"));
        assertEquals("NOT_FOUND", socket.last("command.error").path("data").path("code").asString());
    }

    @Test
    void duplicateCancellationReturnsTheLatestGatewayFailure() throws Exception {
        initialize();
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull()))
                .thenReturn(ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED, "Cancellation unavailable"))
                .thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Run not found"));
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, cancel(requestId));
        TestSocket.await(() -> socket.types().stream().filter("command.error"::equals).count() == 1);
        var first = socket.last("command.error");
        send(socket, cancel(requestId));
        TestSocket.await(() -> socket.types().stream().filter("command.error"::equals).count() == 2);
        var second = socket.last("command.error");

        assertEquals("NOT_IMPLEMENTED", first.path("data").path("code").asString());
        assertEquals("NOT_FOUND", second.path("data").path("code").asString());
        assertEquals("Run not found", second.path("data").path("message").asString());
        assertEquals(requestId.toString(), second.path("requestId").asString());
        assertTrue(second.path("seq").longValue() > first.path("seq").longValue());
        assertEquals(2, socket.types().stream().filter("run.cancelling"::equals).count());
        assertFalse(socket.types().contains("run.cancelled"));
        verify(gateway, times(2)).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
    }

    @Test
    void duplicateSuccessfulCancellationIsDelegatedEachTime() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, cancel(requestId));
        send(socket, cancel(requestId));
        TestSocket.await(() -> socket.types().stream().filter("run.cancelling"::equals).count() == 2);
        assertFalse(socket.types().contains("run.cancelled"));
        assertFalse(socket.types().contains("command.error"));
        verify(gateway, times(2)).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
    }


    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void commandFailuresAreReturnedToTheCancellingRequest(ErrorCode code) throws Exception {
        initialize();
        when(gateway.command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull())).thenReturn(ServiceResult.failure(code, "Cancellation rejected"));
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID requestId = UUID.randomUUID();
        send(socket, cancel(requestId));
        TestSocket.await(() -> socket.types().contains("command.error"));

        assertEquals(code.name(), socket.last("command.error").path("data").path("code").asString());
        assertEquals("Cancellation rejected", socket.last("command.error").path("data").path("message").asString());
        assertEquals(requestId.toString(), socket.last("command.error").path("requestId").asString());
        assertEquals(requestId.toString(), socket.last("run.cancelling").path("requestId").asString());
        assertTrue(socket.last("command.error").path("seq").longValue()
                > socket.last("run.cancelling").path("seq").longValue());
        assertFalse(socket.types().contains("run.cancelled"));
        assertTrue(socket.open.get());
        verify(gateway).command(socket.agentSessionScope, AgentSessionChatStreamGateway.CommandType.STOP, null);
    }

    @Test
    void cancellationAcknowledgementsUseTheCancelRequestAndBracketTheGatewayCall() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID chatId = UUID.randomUUID();
        send(socket, chat(chatId, "work"));
        doAnswer(call -> {
            TestSocket.await(() -> socket.types().contains("run.cancelling"));
            sink.get().emit(new ChatStreamEvent.Cancelled(chatId, CancelReason.USER_CANCELLED));
            return ServiceResult.success();
        }).when(gateway).command(any(), eq(AgentSessionChatStreamGateway.CommandType.STOP), isNull());
        UUID cancelId = UUID.randomUUID();
        send(socket, cancel(cancelId));
        TestSocket.await(() -> socket.types().contains("run.cancelled"));
        assertEquals(cancelId.toString(), socket.last("run.cancelling").path("requestId").asString());
        assertEquals(chatId.toString(), socket.last("run.cancelled").path("requestId").asString());
        assertEquals(1, socket.types().stream().filter("run.cancelled"::equals).count());
    }


    @Test
    void streamCancellationEventRetainsItsChatRequestAfterTheCancelAcknowledgement() throws Exception {
        initialize();
        TestSocket socket = new TestSocket();
        handler.afterConnectionEstablished(socket.session);
        UUID chatId = UUID.randomUUID(), cancelId = UUID.randomUUID();
        send(socket, chat(chatId, "work"));
        send(socket, cancel(cancelId));
        TestSocket.await(() -> socket.types().contains("run.cancelling"));
        assertFalse(socket.types().contains("run.cancelled"));
        sink.get().emit(new ChatStreamEvent.Cancelled(chatId, CancelReason.USER_CANCELLED));
        TestSocket.await(() -> socket.types().contains("run.cancelled"));
        assertEquals(1, socket.types().stream().filter("run.cancelled"::equals).count());
        assertEquals(chatId.toString(), socket.last("run.cancelled").path("requestId").asString());
        assertEquals("USER_CANCELLED", socket.last("run.cancelled").path("data").path("reason").asString());
    }

    private void send(TestSocket socket, String text) throws Exception {
        handler.handleMessage(socket.session, new TextMessage(text));
    }

    private static String chat(UUID id, String message) {
        return "{\"version\":1,\"type\":\"chat\",\"requestId\":\"" + id
                + "\",\"data\":{\"message\":\"" + message + "\"}}";
    }

    private String cancel(UUID id) {
        return "{\"version\":1,\"type\":\"cancel\",\"requestId\":\"" + id
                + "\",\"data\":{}}";
    }
}
