package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.RunState;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessage;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType;
import com.mindrails.backend.agent.api.websocket.OutboundMessageSender.Draft;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class StreamProtocolTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    final UUID requestId = UUID.randomUUID();

    @Test void attachmentsUseVersionIdsAndAllowAnAttachmentOnlyMessage() {
        UUID asset=UUID.randomUUID();
        String json=mapper.writeValueAsString(Map.of("version",1,"type","chat","requestId",requestId,"data",Map.of("message","","assetVersionIds",List.of(asset))));
        var command=(ClientCommand.Chat)StreamProtocol.decode(json);
        assertEquals(List.of(asset),command.assetVersionIds());assertFalse(command.message().isBlank());
        assertThrows(RuntimeException.class,()->StreamProtocol.decode(json.replace(asset.toString(),"../../secret")));
    }

    @Test
    void validatesTheFullClientEnvelopeAndDoesNotAllowSessionSwitches() {
        String valid = "{\"version\":1,\"type\":\"chat\",\"requestId\":\"" + requestId + "\",\"data\":{\"message\":\"你好\"}}";
        assertEquals(new ClientCommand.Chat(requestId, "你好"), StreamProtocol.decode(valid));
        for (String invalid : List.of(valid + " {}", valid.replace("你好", " "), valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"version\":1.0"), valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid.replace("\"version\":1", "\"version\":4294967297"),
                valid.replace("\"message\":", "\"sessionId\":\"other\",\"message\":"),
                valid.replace(requestId.toString(), "1-1-1-1-1"), valid.replace("\"chat\"", "\"unknown\""))) {
            assertThrows(RuntimeException.class, () -> StreamProtocol.decode(invalid), invalid);
        }
    }

    @Test
    void cancelTargetsTheBoundSessionWithoutARunId() {
        String valid = "{\"version\":1,\"type\":\"cancel\",\"requestId\":\"" + requestId + "\",\"data\":{}}";
        assertEquals(new ClientCommand.Cancel(requestId), StreamProtocol.decode(valid));
        assertThrows(RuntimeException.class, () -> StreamProtocol.decode(
                valid.replace("\"data\":{}", "\"data\":{\"runId\":\"" + UUID.randomUUID() + "\"}")));
        assertThrows(RuntimeException.class, () -> StreamProtocol.decode(valid.replace("\"data\":{}", "\"data\":null")));
    }

    @ParameterizedTest
    @CsvSource({
            "CONNECTION_READY, connection.ready", "COMMAND_ERROR, command.error", "CHAT_ACCEPTED, chat.accepted",
            "RUN_CANCELLING, run.cancelling", "RUN_STARTED, run.started", "THINKING_DELTA, thinking.delta",
            "ANSWER_DELTA, answer.delta", "RETRIEVAL_COMPLETED, retrieval.completed",
            "TOOL_ARGUMENTS_DELTA, tool.arguments.delta", "MODEL_RESPONSE_COMPLETED, model.response.completed",
            "TOOL_STARTED, tool.started", "TOOL_COMPLETED, tool.completed", "TOOL_FAILED, tool.failed",
            "RUN_COMPLETED, run.completed", "RUN_CANCELLED, run.cancelled", "RUN_FAILED, run.failed"
    })
    void serializesMessageTypesAsStableWireValues(ServerMessageType type, String wireType) {
        var message = new ServerMessage<>(1, type, UUID.randomUUID(), UUID.randomUUID(),
                requestId, 1, Instant.now(), Map.of());
        var json = mapper.readTree(mapper.writeValueAsString(message));
        assertEquals(wireType, json.path("type").asString());
        assertEquals(requestId.toString(), json.path("requestId").asString());
        assertFalse(json.has("runId"));
    }

    @Test
    void serializesCommunicationProvenanceOnEnqueueAndStartedSnapshots() {
        var source = new com.mindrails.backend.agent.model.SessionMessageSource(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, Instant.now(), 1);
        var input = new ChatStreamEvent.Enqueued(requestId, "hello", Instant.now(), source);
        var enqueued = StreamProtocol.map(input);
        assertEquals("message.enqueued", enqueued.type().value());
        var wire = mapper.readTree(mapper.writeValueAsString(enqueued.data()));
        assertEquals(source.sourceSessionId().toString(), wire.path("source").path("sourceSessionId").asString());
        var started = mapper.readTree(mapper.writeValueAsString(StreamProtocol.map(new ChatStreamEvent.Started(requestId, input)).data()));
        assertEquals("hello", started.path("input").path("message").asString());
        assertEquals(requestId.toString(), started.path("input").path("requestId").asString());
    }

    @Test
    void mapsEveryDomainEventToAStableWireTypeAndTerminalState() {
        UUID messageId = UUID.randomUUID();
        var error = new ChatStreamEvent.Error(ErrorCode.INTERNAL_ERROR, "Safe error");
        List<ChatStreamEvent> events = List.of(new ChatStreamEvent.Started(requestId), new ChatStreamEvent.ThinkingDelta(requestId, 1, messageId, "thinking"),
                new ChatStreamEvent.AnswerDelta(requestId, 1, messageId, "answer"), new ChatStreamEvent.Retrieved(requestId, List.of()),
                new ChatStreamEvent.ToolArgumentsDelta(requestId, 1, "tool1", null, "{"),
                new ChatStreamEvent.ModelResponseCompleted(requestId, 1, messageId, "TOOL_EXECUTION", List.of(new ChatStreamEvent.ToolCall("tool1", "ls", Map.of()))),
                new ChatStreamEvent.ToolStarted(requestId, 1, "tool1", "ls", Map.of()), new ChatStreamEvent.ToolCompleted(requestId, 1, "tool1", "ls", "ok", 12L),
                new ChatStreamEvent.ToolFailed(requestId, 1, "tool1", "ls", error), new ChatStreamEvent.Completed(requestId, messageId, "answer", "STOP", null),
                new ChatStreamEvent.Cancelled(requestId, com.mindrails.backend.agent.api.websocket.protocol.CancelReason.USER_CANCELLED), new ChatStreamEvent.Failed(requestId, error));
        assertEquals(List.of("run.started", "thinking.delta", "answer.delta", "retrieval.completed", "tool.arguments.delta", "model.response.completed",
                "tool.started", "tool.completed", "tool.failed", "run.completed", "run.cancelled", "run.failed"), events.stream().map(e -> StreamProtocol.map(e).type().value()).toList());
        assertNull(StreamProtocol.map(events.get(8)).terminalState()); // Tool failure is not automatically run failure.
        assertEquals(RunState.COMPLETED, StreamProtocol.map(events.get(9)).terminalState());
        assertEquals(RunState.CANCELLED, StreamProtocol.map(events.get(10)).terminalState());
        assertEquals(RunState.FAILED, StreamProtocol.map(events.get(11)).terminalState());
        events.forEach(e -> {
            var mapped = StreamProtocol.map(e);
            var draft = Draft.from(mapped);
            assertEquals(e.requestId(), mapped.requestId());
            assertEquals(mapped.type(), draft.type());
            assertEquals(requestId, draft.requestId());
            assertSame(mapped.data(), draft.data());
            var message = new ServerMessage<>(1, draft.type(), UUID.randomUUID(), UUID.randomUUID(),
                    draft.requestId(), 1, Instant.now(), draft.data());
            var json = mapper.readTree(mapper.writeValueAsString(message));
            assertEquals(requestId.toString(), json.path("requestId").asString());
            assertFalse(json.path("data").has("requestId"));
        });
    }

    @Test
    void boundsOnlyDisplayCopiesAndSendsStartedStateWithoutRepeatingArguments() {
        String result = "你好🌏".repeat(4000);
        var payload = (StreamPayloads.ToolCompleted) StreamProtocol.map(new ChatStreamEvent.ToolCompleted(requestId, 1, "t", "tool", result, 1L)).data();
        assertFalse(payload.truncated()); // The preview already ends with an ellipsis.
        assertEquals(201, payload.result().codePointCount(0, payload.result().length()));
        assertTrue(payload.result().endsWith("…"));
        assertFalse(Character.isHighSurrogate(payload.result().charAt(payload.result().length() - 1)));
        var started = (StreamPayloads.ToolStarted) StreamProtocol.map(new ChatStreamEvent.ToolStarted(requestId, 1, "t", "tool", Map.of("text", result))).data();
        assertFalse(started.argumentsOmitted());
        assertNull(started.arguments());
        assertEquals(result, new ChatStreamEvent.ToolCompleted(requestId, 1, "t", "tool", result, 1L).result());
    }
}
