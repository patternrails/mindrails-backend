package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.common.config.FileStorageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.internal.llm.ConversationRag;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.Asset;
import com.mindrails.backend.asset.model.AssetVersion;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.AssetConversationRetriever;
import com.mindrails.backend.asset.service.AssetKnowledgeService;
import com.mindrails.backend.asset.service.AssetStorageService;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.tool.ToolErrorHandler;
import com.mindrails.backend.tool.spec.ReadTool;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class SessionVisionIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j46sAAAAASUVORK5CYII=";

    @Test
    void chatCompletionsContinuesAfterReadingAnImageAndAfterReloadingItsToolResult(@TempDir Path directory)
            throws Exception {
        var image = Files.write(directory.resolve("pixel.png"), Base64.getDecoder().decode(PNG));
        var arguments = JSON.writeValueAsString(Map.of("path", image.toString()));
        var requests = new LinkedBlockingQueue<JsonNode>();
        var callCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            String choice = callCount.getAndIncrement() == 0 ? """
                    {"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_read",
                    "type":"function","function":{"name":"read","arguments":%s}}]},"finish_reason":"tool_calls"}
                    """.formatted(JSON.writeValueAsString(arguments)) : """
                    {"index":0,"delta":{"role":"assistant","content":"I received the tool result."},"finish_reason":"stop"}
                    """;
            String chunk = "{\"id\":\"response\",\"object\":\"chat.completion.chunk\",\"model\":\"test\",\"choices\":["
                    + choice.replace("\n", "") + "]}";
            byte[] bytes = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            var model = OpenAiStreamingChatModel.builder().apiKey("fixture").modelName("test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
            var session = Session.builder().id(UUID.randomUUID()).ownerId(UUID.randomUUID()).memoryId(UUID.randomUUID())
                    .provider(Provider.builder().streaming(true).streamingChatModel(model).build()).build();
            var memory = new InMemoryChatMemoryStore();
            var factory = TestLlmClients.create(List.of(new ReadTool()), () -> memory, new ChatMemoryProperties());
            var client = ((ServiceResult.Success<com.mindrails.backend.agent.model.LlmClient>)
                    factory.createClientForLoop(session)).value();
            assertThat(TestLlmClients.chat(client, session.getMemoryId(), "Read the image").content())
                    .isEqualTo("I received the tool result.");
            assertThat(ChatMessageSerializer.messagesToJson(memory.getMessages(session.getMemoryId())))
                    .contains(PNG);

            var reloaded = ((ServiceResult.Success<com.mindrails.backend.agent.model.LlmClient>)
                    factory.createClientForLoop(session)).value();
            assertThat(TestLlmClients.chat(reloaded, session.getMemoryId(), "Continue").content())
                    .isEqualTo("I received the tool result.");
            assertThat(requests).hasSize(3);
            requests.remove();
            for (var request : requests) {
                var tool = request.path("messages").valueStream()
                        .filter(message -> "tool".equals(message.path("role").asString())).findFirst().orElseThrow();
                assertThat(tool.path("tool_call_id").asString()).isEqualTo("call_read");
                assertThat(tool.path("content").asString()).contains("Read image", "Non-text tool output omitted");
                assertThat(request.toString()).doesNotContain(PNG);
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imageReachesTheProviderAfterTextRagAndOnlyTextAndReferencesSurviveTheTurn(boolean qwen) throws Exception {
        var requests = new LinkedBlockingQueue<JsonNode>();
        var paths = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            requests.add(JSON.readTree(exchange.getRequestBody()));
            String response = !qwen ? """
                    {"id":"response","object":"chat.completion","model":"vision-test","choices":[
                    {"index":0,"message":{"role":"assistant","content":"The image shows a pixel."},"finish_reason":"stop"}]}
                    """ : path.contains("/multimodal-generation/") ? """
                    {"request_id":"response","output":{"choices":[{"finish_reason":"stop","message":
                    {"role":"assistant","content":[{"text":"The image shows a pixel."}]}}]}}
                    """ : """
                    {"request_id":"response","output":{"choices":[{"finish_reason":"stop","message":
                    {"role":"assistant","content":"The image shows a pixel."}}]}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatModel model = qwen
                    ? QwenChatModel.builder().apiKey("fixture").modelName("vision-test").baseUrl(baseUrl + "/api/v1").build()
                    : OpenAiChatModel.builder().apiKey("fixture").modelName("vision-test").baseUrl(baseUrl + "/v1").build();
            var session = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
                    .ownerId(UUID.randomUUID()).memoryId(UUID.randomUUID())
                    .provider(Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build())
                    .build();
            var sessions = mock(SessionRegistry.class);
            when(sessions.findManaged(session.getId())).thenReturn(Optional.of(session));
            when(sessions.find(session.getId())).thenReturn(Optional.of(new ReadOnlySessionRegistry.SessionView(
                    session.getId(), session.getParentAgentId(), null, false)));
            var registry = mock(AssetRegistry.class);
            var storage = mock(AssetStorageService.class);
            var attached = new ArrayList<UUID>();
            when(registry.transaction(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
            when(registry.sessionVersions(session.getId())).thenAnswer(call -> List.copyOf(attached));
            doAnswer(call -> { attached.add(call.getArgument(1)); return null; }).when(registry)
                    .attach(eq(session.getId()), any());
            var image = UUID.randomUUID();
            var asset = UUID.randomUUID();
            byte[] png = Base64.getDecoder().decode(PNG);
            when(storage.version(image)).thenReturn(AssetVersion.builder().id(image).assetId(asset)
                    .filename("pixel.png").mediaType("image/png").state("READY").bytes(png.length).build());
            when(storage.asset(asset)).thenReturn(new Asset(asset, session.getOwnerId(), "pixel.png", "FILE", null));
            when(storage.open(image)).thenAnswer(call -> new ByteArrayInputStream(png));
            var retriever = new AssetConversationRetriever(storage, registry, mock(AssetKnowledgeService.class),
                    null, sessions, new AssetStorageProperties());
            var archive = new LinkedBlockingQueue<HistoryRecord>();
            var history = TestHistory.create(TestHistory.completed(archive::add), null);
            var metrics = TestMetrics.disabled();
            var memory = com.mindrails.backend.memory.TestMemory.inMemory(20);
            var rag = new ConversationRag(List.of(retriever, query -> List.of(
                    Content.from(TextSegment.from("Context for " + query.text())))), 10000, history, retriever);
            var factory = new LlmClientFactory(List.of(), memory, new SkillCatalog(new SkillFactory(new FileStorageProperties(Path.of("build", "test-storage")))),
                    new AiServiceListenerFactory(history, metrics), TestLlmClients.noMcpTools(),
                    TestLlmClients.disabledKnowledgeTools(), rag, new ToolErrorHandler(),
                    new SessionDeliverableToolAdapter(TestLlmClients.sessionFiles()), new TaskDeliverableToolAdapter(null),
                    null, retriever);
            var loops = new DefaultLoopSessionStreamService(sessions, factory, history, metrics, retriever);
            try {
                var terminal = new LinkedBlockingQueue<ChatStreamEvent>();
                loops.bind(session.getId(), event -> {
                    if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed)
                        terminal.add(event);
                });
                var first = UUID.randomUUID();
                assertThat(loops.enqueue(session.getId(), new ClientCommand.Chat(first, "Describe this", List.of(image))))
                        .isInstanceOf(ServiceResult.Success.class);
                assertThat(terminal.poll(5, TimeUnit.SECONDS)).isInstanceOf(ChatStreamEvent.Completed.class);
                assertThat(paths.remove()).endsWith(qwen ? "/multimodal-generation/generation" : "/chat/completions");
                var request = requests.remove();
                var sent = (qwen ? request.path("input") : request).path("messages");
                assertThat(sent.toString()).contains("Context for Describe this");
                var imagePart = sent.get(sent.size() - 1).path("content").get(1);
                var url = qwen ? imagePart.path("image") : imagePart.path("image_url").path("url");
                assertThat(url.asString()).isEqualTo("data:image/png;base64," + PNG);
                var archived = archive.poll(5, TimeUnit.SECONDS);
                assertThat(archived).isNotNull();
                assertThat(archived.requestId()).isEqualTo(first);
                var ref = JSON.readTree(archived.metadata()).path("attachments").get(0);
                assertThat(ref.path("versionId").asString()).isEqualTo(image.toString());
                assertThat(ref.path("name").asString()).isEqualTo("pixel.png");
                assertThat(archived.messages()).contains("Describe this", "The image shows a pixel.").doesNotContain(PNG);
                assertThat(archived.metadata()).doesNotContain(PNG);

                assertThat(loops.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "What did you see?")))
                        .isInstanceOf(ServiceResult.Success.class);
                assertThat(terminal.poll(5, TimeUnit.SECONDS)).isInstanceOf(ChatStreamEvent.Completed.class);
                assertThat(paths.remove()).endsWith(qwen ? "/text-generation/generation" : "/chat/completions");
                assertThat(requests.remove().toString()).contains("Describe this", "The image shows a pixel.",
                        "Context for What did you see?").doesNotContain(PNG, "image_url", "pixel.png");
                assertThat(archive.poll(5, TimeUnit.SECONDS).metadata()).doesNotContain("attachments");
                assertThat(ChatMessageSerializer.messagesToJson(memory.buildChatMemory(session.getMemoryId()).messages()))
                        .contains("Describe this", "The image shows a pixel.").doesNotContain(PNG, "Context for");
                verify(storage, times(1)).open(image);
                verify(storage, never()).blocks(image);
            } finally {
                loops.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }
}
