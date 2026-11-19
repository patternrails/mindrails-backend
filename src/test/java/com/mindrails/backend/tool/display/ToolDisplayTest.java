package com.mindrails.backend.tool.display;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import com.mindrails.backend.agent.model.TaskExecution;
import tools.jackson.databind.json.JsonMapper;

class ToolDisplayTest {
    final JsonMapper json = JsonMapper.builder().build();
    @Test void writeHidesContentInArgumentsResultsAndArchivedMessages() {
        String body = "private file body".repeat(10000);
        var args = Map.of("path", "/tmp/report.html", "content", body);
        assertEquals(Map.of("path", "/tmp/report.html"), ToolDisplay.arguments("write", args));
        assertEquals(body, args.get("content"));
        assertNull(ToolDisplay.result("write", body, false));
        assertEquals("permission denied", ToolDisplay.result("write", "permission denied", true));
        var request = ToolExecutionRequest.builder().id("write-1").name("write").arguments(json.writeValueAsString(args)).build();
        String archive = ChatMessageSerializer.messagesToJson(List.of(AiMessage.from(request), ToolExecutionResultMessage.from(request, body)));
        String preview = ToolDisplay.messages(archive);
        assertFalse(preview.contains(body));
        assertFalse(preview.contains("content" + "\\\""));
        assertTrue(preview.contains("/tmp/report.html"));
        assertTrue(preview.length() < 1000);
        assertTrue(archive.contains(body));
    }
    @Test void shellAndUnknownToolsAreBoundedWithoutBreakingUnicode() {
        String body = "你好🌏".repeat(10000);
        String preview = ToolDisplay.result("bash", json.writeValueAsString(Map.of("output", body, "exitCode", 9, "timedOut", true)), false);
        assertTrue(preview.startsWith("exitCode=9 · timed out"));
        assertTrue(preview.endsWith("…"));
        assertEquals(201, preview.codePointCount(0, preview.length()));
        assertEquals(201, ToolDisplay.text(body).codePointCount(0, ToolDisplay.text(body).length()));
        assertEquals(201, ToolDisplay.result("mcp_unknown", body, false).codePointCount(0, ToolDisplay.result("mcp_unknown", body, false).length()));
        assertTrue(json.writeValueAsString(ToolDisplay.arguments("mcp_unknown", Map.of("huge", body))).length() < 500);
    }
    @Test void taskLiveEventsAndRestoredRecordsNeverReturnFullToolPayloadsOrModelRequests() {
        String body = "large content".repeat(10000);
        var call = new TaskExecution.ToolCall("w", "write", json.writeValueAsString(Map.of("path", "/tmp/a", "content", body)), body, false, 10L);
        var live = ToolUiProjection.taskEvent("tool.completed", call);
        assertFalse(json.writeValueAsString(live).contains(body));
        assertFalse(ToolDisplay.object(live).containsKey("arguments"));
        assertNull(ToolDisplay.object(live).get("result"));
        assertFalse(ToolDisplay.object(ToolUiProjection.taskEvent("model.requested", Map.of("round", 1, "messages", body))).containsKey("messages"));
        assertNull(ToolDisplay.object(ToolUiProjection.taskEvent("tool.started", Map.of("toolName", "write", "arguments", body))).get("arguments"));
        var execution = TaskExecution.builder().toolExecutions(List.of(call)).records(List.of(
                new TaskExecution.Entry(1, java.time.Instant.now(), "tool.completed", json.readValue(json.writeValueAsString(call), Map.class)))).build();
        var projected = ToolUiProjection.task(execution);
        assertFalse(json.writeValueAsString(projected).contains(body));
        assertTrue(execution.toolExecutions().getFirst().arguments().contains(body));
    }
}
