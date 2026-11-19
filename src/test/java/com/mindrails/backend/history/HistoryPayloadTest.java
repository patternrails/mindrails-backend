package com.mindrails.backend.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.history.api.web.HistoryController;
import com.mindrails.backend.history.api.web.HistoryErrorAdvice;
import com.mindrails.backend.history.api.web.HistoryPreview;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.service.DefaultHistoryService;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import com.mindrails.backend.identity.ResourceAccess;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class HistoryPayloadTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void listAndPreviewExcludeArchiveJsonWhileExplicitReadsPreserveOriginals() throws Exception {
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        String longText = "😀".repeat(9000);
        var messages = List.of(
                Map.of("type", "USER", "contents", List.of(Map.of("type", "TEXT", "text", longText)), "custom", "private extension"),
                Map.of("type", "AI", "text", "answer", "thinking", longText, "toolExecutionRequests", List.of(Map.of("name", "write", "arguments", longText))),
                Map.of("type", "FUTURE_MESSAGE", "payload", Map.of("text", longText, "custom", List.of(1, true))));
        String metadata = JSON.writeValueAsString(Map.of("partial", Map.of("answer", longText), "custom", Map.of("flag", true), "usage", Map.of("totalTokens", 42), "finishReason", "STOP"));
        var record = new HistoryRecord(id, owner, null, UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                HistoryRecord.Transport.STREAM, HistoryRecord.Status.CANCELLED,
                JSON.writeValueAsString(messages), metadata, Instant.now(), Instant.now(), 1);
        var store = new InMemoryHistoryStore();
        store.insert(record);
        var mvc = MockMvcBuilders.standaloneSetup(new HistoryController(new DefaultHistoryService(store)))
                .setControllerAdvice(new HistoryErrorAdvice()).build();
        try (var ignored = ResourceAccess.as(owner)) {
            mvc.perform(get("/api/history")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].metadata").doesNotExist())
                    .andExpect(jsonPath("$.items[0].messages").doesNotExist())
                    .andExpect(jsonPath("$.items[0].totalTokens").value(42))
                    .andExpect(jsonPath("$.items[0].finishReason").value("STOP"));
            var response = mvc.perform(get("/api/history/" + id + "/preview")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata").doesNotExist())
                    .andExpect(jsonPath("$.messages[0].contents").doesNotExist())
                    .andExpect(jsonPath("$.messages[0].custom").doesNotExist())
                    .andExpect(jsonPath("$.messages[0].truncated").value(true))
                    .andExpect(jsonPath("$.messages[1].toolExecutionRequests").doesNotExist())
                    .andExpect(jsonPath("$.messages[2].payload").doesNotExist())
                    .andExpect(jsonPath("$.partial.truncated").value(true)).andReturn();
            var preview = JSON.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var body = preview.path("messages").get(0).path("text").asString();
            assertEquals(HistoryPreview.TEXT_LIMIT + 1, body.codePointCount(0, body.length()));
            assertTrue(body.endsWith("…"));
            assertTrue(response.getResponse().getContentAsByteArray().length < 100_000);
            mvc.perform(get("/api/history/" + id + "/metadata")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.custom.flag").value(true))
                    .andExpect(jsonPath("$.metadata.partial.answer").value(longText))
                    .andExpect(jsonPath("$.messages").doesNotExist());
            for (int index = 0; index < messages.size(); index++) {
                var raw = mvc.perform(get("/api/history/" + id + "/messages/" + index)).andExpect(status().isOk()).andReturn();
                assertEquals(JSON.valueToTree(messages.get(index)), JSON.readTree(raw.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message"));
            }
            mvc.perform(get("/api/history/" + id + "/messages/-1")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/history/" + id + "/messages/3")).andExpect(status().isNotFound());
            // Session restoration still receives the existing conversation contract.
            mvc.perform(get("/api/history/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages[0].contents[0].text").value(longText));
        }
        try (var ignored = ResourceAccess.as(UUID.randomUUID())) {
            for (String suffix : List.of("/preview", "/metadata", "/messages/0")) {
                mvc.perform(get("/api/history/" + id + suffix)).andExpect(status().isNotFound());
            }
        }
    }

    @Test
    void capsToolCallsAndPreservesShortDisplayContent() {
        var calls = java.util.stream.IntStream.range(0, 100).mapToObj(i -> Map.of("name", "read", "arguments", "{\"path\":\"file\"}")).toList();
        var record = new HistoryRecord(UUID.randomUUID(), null, null, UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                HistoryRecord.Transport.STREAM, HistoryRecord.Status.COMPLETED,
                JSON.writeValueAsString(List.of(Map.of("type", "AI", "text", "short answer", "thinking", "short thought", "toolExecutionRequests", calls))),
                "{}", Instant.now(), Instant.now(), 1);
        var message = HistoryPreview.from(record).messages().getFirst();
        assertEquals("short answer", message.text());
        assertEquals("short thought", message.thinking());
        assertEquals(HistoryPreview.TOOL_LIMIT, message.toolCalls().size());
        assertTrue(message.truncated());
    }
}
