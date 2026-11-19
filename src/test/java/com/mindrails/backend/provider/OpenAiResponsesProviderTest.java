package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesProviderTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String SUMMARY = "Check the ping tool before answering.";

    @ParameterizedTest
    @CsvSource({"false,medium", "true,medium", "false,none", "true,none", "false,default", "true,default"})
    void usesResponsesWithReasoningAndReplaysToolResults(boolean streaming, String level) throws Exception {
        boolean summaries = !"none".equals(level);
        String effort = "default".equals(level) ? null : level;
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var paths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(mapper.readTree(exchange.getRequestBody()));
            boolean toolRound = requests.size() == 1;
            String response = response(toolRound, summaries);
            String body = streaming ? stream(response, toolRound, summaries) : response;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var definition = new ProviderDefinition(null, "openai", "OpenAi", streaming,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "gpt-6-sol",
                    "default".equals(level) ? null : summaries, effort, null, 1050000, 4096);
            var provider = new ProvidersConfiguration()
                    .loadDefinitions(List.of(definition), "openai").getFirst();
            var user = UserMessage.from("Use ping");
            var tool = ToolSpecification.builder().name("ping").description("Return pong").build();
            var thinking = new CopyOnWriteArrayList<String>();
            var first = chat(provider, ChatRequest.builder().messages(user).toolSpecifications(tool).build(), thinking);
            assertEquals(summaries ? SUMMARY : null, first.aiMessage().thinking());
            assertEquals(streaming && summaries ? SUMMARY : "", String.join("", thinking));
            var call = first.aiMessage().toolExecutionRequests().getFirst();
            assertEquals("call_ping", call.id());
            assertEquals("ping", call.name());
            assertEquals("{}", call.arguments());
            var result = ToolExecutionResultMessage.from(call, "pong");
            var second = chat(provider, ChatRequest.builder()
                    .messages(user, first.aiMessage(), result).toolSpecifications(tool).build(), thinking);
            assertEquals("pong", second.aiMessage().text());

            assertEquals(List.of("/v1/responses", "/v1/responses"), paths);
            for (var request : requests) {
                assertEquals("gpt-6-sol", request.path("model").asText());
                if (effort == null) assertFalse(request.path("reasoning").has("effort"));
                else assertEquals(effort, request.path("reasoning").path("effort").asText());
                if (summaries) assertEquals("auto", request.path("reasoning").path("summary").asText());
                else assertFalse(request.path("reasoning").has("summary"));
                assertEquals(4096, request.path("max_output_tokens").asInt());
                assertFalse(request.path("store").asBoolean(true));
                assertEquals("reasoning.encrypted_content", request.path("include").get(0).asText());
                assertEquals("function", request.path("tools").get(0).path("type").asText());
                assertEquals("ping", request.path("tools").get(0).path("name").asText());
                assertFalse(request.has("messages"));
                assertFalse(request.has("reasoning_effort"));
                assertEquals(streaming, request.path("stream").asBoolean());
            }
            var input = requests.get(1).path("input");
            assertEquals("reasoning", input.get(1).path("type").asText());
            assertEquals("encrypted-test-reasoning", input.get(1).path("encrypted_content").asText());
            if (summaries) assertEquals(SUMMARY, input.get(1).path("summary").get(0).path("text").asText());
            assertEquals("function_call", input.get(2).path("type").asText());
            assertEquals("call_ping", input.get(2).path("call_id").asText());
            assertEquals("function_call_output", input.get(3).path("type").asText());
            assertEquals("call_ping", input.get(3).path("call_id").asText());
            assertEquals("pong", input.get(3).path("output").asText());
        } finally {
            server.stop(0);
        }
    }

    private ChatResponse chat(Provider provider, ChatRequest request, List<String> thinking) throws Exception {
        if (!provider.getStreaming()) return provider.getChatModel().chat(request);
        var completed = new CompletableFuture<ChatResponse>();
        provider.getStreamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) {}
            @Override public void onPartialThinking(PartialThinking partial, PartialThinkingContext context) {
                thinking.add(partial.text());
            }
            @Override public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
            @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
        });
        return completed.get(10, TimeUnit.SECONDS);
    }

    private String response(boolean toolRound, boolean summaries) {
        String summary = summaries ? "[{\"type\":\"summary_text\",\"text\":\"" + SUMMARY + "\"}]" : "[]";
        String output = toolRound ? """
                [{"id":"rs_1","type":"reasoning","summary":%s,"encrypted_content":"encrypted-test-reasoning"},
                 {"id":"fc_1","type":"function_call","call_id":"call_ping","name":"ping","arguments":"{}","status":"completed"}]
                """.formatted(summary) : """
                [{"id":"msg_1","type":"message","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"pong","annotations":[]}]}]
                """;
        return """
                {"id":"resp_%s","object":"response","created_at":1793404800,"status":"completed",
                 "model":"gpt-6-sol","output":%s}
                """.formatted(toolRound ? "tool" : "text", output);
    }

    private String stream(String response, boolean toolRound, boolean summaries) {
        String events = event("response.created", "\"response\":" + response);
        if (toolRound) {
            if (summaries) {
                events += event("response.reasoning_summary_text.delta", """
                        "output_index":0,"summary_index":0,"item_id":"rs_1","delta":"%s"
                        """.formatted(SUMMARY));
            }
            events += event("response.output_item.added", """
                    "output_index":0,"item":{"id":"fc_1","type":"function_call",
                    "call_id":"call_ping","name":"ping","arguments":"","status":"in_progress"}
                    """);
            events += event("response.function_call_arguments.done", """
                    "output_index":0,"item_id":"fc_1","arguments":"{}"
                    """);
        } else {
            events += event("response.output_text.delta", """
                    "output_index":0,"content_index":0,"item_id":"msg_1","delta":"pong","logprobs":[]
                    """);
        }
        return events + event("response.completed", "\"response\":" + response);
    }

    private String event(String type, String fields) {
        // SSE data occupies a single line even when the JSON fixtures are formatted across lines.
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\",\"sequence_number\":0,"
                + fields.replace("\n", "") + "}\n\n";
    }
}
