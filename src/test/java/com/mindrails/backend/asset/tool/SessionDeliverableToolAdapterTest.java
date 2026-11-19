package com.mindrails.backend.asset.tool;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.asset.api.web.SessionDeliverablesController;
import com.mindrails.backend.tool.ToolFailure;
import com.mindrails.backend.tool.spec.WriteTool;
import com.mindrails.backend.tool.spec.EditTool;
import com.mindrails.backend.metrics.TestMetrics;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SessionDeliverableToolAdapterTest {
    @TempDir Path root;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    private SessionDeliverables files() {
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        sessions.insertIfAbsent(Session.builder().id(a).build());
        sessions.insertIfAbsent(Session.builder().id(b).build());
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        return new SessionDeliverables(properties, sessions);
    }

    private String call(SessionDeliverables files, UUID session, AiServiceTool tool, Map<String, Object> args) {
        return execute(new SessionDeliverableToolAdapter(files).adapt(tool, session), args);
    }

    private String execute(AiServiceTool tool, Map<String, Object> args) {
        return tool.toolExecutor().executeWithContext(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(tool.name()).arguments(new JsonMapper().writeValueAsString(args)).build(),
                InvocationContext.builder().chatMemoryId(UUID.randomUUID()).build()).resultText();
    }

    @Test void outputsAreSessionOwnedAndPersistAcrossServiceRestart() throws Exception {
        var files = files();
        var bindings = new SessionDeliverableToolAdapter(files);
        var tools = new WriteTool(root).buildTools(TestMetrics.disabled());
        var first = bindings.adapt(tools.getFirst(), a);
        var second = bindings.adapt(tools.getFirst(), b);
        String result = execute(first, Map.of("path", "报告/budget.csv", "content", "name,amount\n旅行,100\n"));
        assertTrue(result.contains("downloadUrl")); assertTrue(result.contains("/sessions/" + a));
        execute(second, Map.of("path", "报告/budget.csv", "content", "other session"));
        var output = files.list(a).getFirst();
        assertEquals("报告/budget.csv", output.path());
        assertEquals("name,amount\n旅行,100\n", Files.readString(files.download(a, output.id())));
        assertEquals("other session", Files.readString(files.download(b, files.list(b).getFirst().id())));
        assertEquals(output, files().list(a).getFirst());
        var response = new SessionDeliverablesController(files).download(a, output.id());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("attachment"));
        assertEquals(Files.size(files.download(a, output.id())), response.getHeaders().getContentLength());
    }

    @Test void editsPublishOnlyAfterSuccessAndFailedEditsPreserveOriginal() throws Exception {
        var files = files(); var write = new WriteTool(root).buildTools(TestMetrics.disabled()).getFirst();
        var edit = new EditTool(root).buildTools(TestMetrics.disabled()).getFirst();
        call(files, a, write, Map.of("path", "report.md", "content", "original"));
        var id = files.list(a).getFirst().id();
        call(files, a, edit, Map.of("path", "report.md", "edits", List.of(Map.of("oldText", "original", "newText", "updated"))));
        assertEquals("updated", Files.readString(files.download(a, id)));
        assertThrows(RuntimeException.class, () -> call(files, a, edit, Map.of("path", "report.md", "edits", List.of(Map.of("oldText", "missing", "newText", "oops")))));
        assertEquals("updated", Files.readString(files.download(a, id)));
        assertEquals(1, files.list(a).size());
        try (var paths = Files.list(files.resolve(a, "report.md").getParent())) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".pending-")));
        }
    }

    @Test void sessionsCanWriteAndEditSharedFilesInsideTheStorageRoot() throws Exception {
        var files = files();
        var write = new WriteTool(root).buildTools(TestMetrics.disabled()).getFirst();
        Path shared = root.resolve("shared/report.txt");
        String result = call(files, a, write, Map.of("path", shared.toString(), "content", "shared"));
        assertEquals("shared", Files.readString(shared));
        assertFalse(new JsonMapper().readTree(result).has("deliverable"));

        var edit = new EditTool(root).buildTools(TestMetrics.disabled()).getFirst();
        call(files, b, edit, Map.of("path", files.outputDirectory(b).relativize(shared).toString(),
                "edits", List.of(Map.of("oldText", "shared", "newText", "updated"))));
        assertEquals("updated", Files.readString(shared));

        Path outside = root.resolveSibling(root.getFileName() + "-outside");
        assertThrows(ToolFailure.class, () -> call(files, a, write,
                Map.of("path", outside.resolve("report.txt").toString(), "content", "outside")));
        assertFalse(Files.exists(outside));
        assertTrue(files.list(a).isEmpty());
    }

    @Test void shellFilesArePublishedAndCanBeReadUsingTheirReturnedPaths() throws Exception {
        var files = files();
        var bash = new com.mindrails.backend.tool.spec.BashTool().buildTools(TestMetrics.disabled()).getFirst();
        String result = call(files, a, bash, Map.of("path", root.toString(), "command", "printf 'item,total\\nTravel,120\\n' > report.csv"));
        assertTrue(result.contains("downloadUrl"));
        assertEquals("report.csv", files.list(a).getFirst().name());
        assertFalse(Files.exists(root.resolve("report.csv")));
        var read = new com.mindrails.backend.tool.spec.ReadTool().buildTools(TestMetrics.disabled()).getFirst();
        assertTrue(execute(read, Map.of("path", files.download(a, files.list(a).getFirst().id()).toString())).contains("Travel,120"));
        var ls = new com.mindrails.backend.tool.spec.LsTool().buildTools(TestMetrics.disabled()).getFirst();
        assertTrue(execute(ls, Map.of("path", files.outputDirectory(a).toString())).contains("report.csv"));
        assertTrue(files.list(b).isEmpty());
    }

    @Test void realClientToolLoopPublishesLinksAndSessionDeletionCleansThem() throws Exception {
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        var files = new SessionDeliverables(properties, sessions);
        Path reference = root.resolve("reference.txt");
        Files.writeString(reference, "Original read tool marker");
        String relativeReference = Path.of("").toAbsolutePath().relativize(reference).toString();
        var requests = new ArrayList<dev.langchain4j.model.chat.request.ChatRequest>();
        var model = new dev.langchain4j.model.chat.ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                requests.add(request);
                var answer = requests.size() == 1 ? dev.langchain4j.data.message.AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("write-report").name("write")
                                .arguments(
                                                                        "{\"path\":\"report.csv\",\"content\":\"item,total\\n"
                                                                            + "Travel,120\"}").build(),
                        ToolExecutionRequest.builder().id("read-reference").name("read")
                                .arguments(new JsonMapper().writeValueAsString(Map.of("path", relativeReference))).build()))
                        : dev.langchain4j.data.message.AiMessage.from("[\"Report saved\"]");
                return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(answer).build();
            }
        };
        var session = Session.builder().id(a).memoryId(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
                .provider(com.mindrails.backend.provider.Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build()).build();
        sessions.insertIfAbsent(session);
        new ApplicationContextRunner()
                .withUserConfiguration(LlmClientFactory.class, com.mindrails.backend.memory.TestMemory.Wiring.class)
                .withBean(SessionDeliverables.class, () -> files)
                .withBean("tools", List.class, () -> new com.mindrails.backend.tool.CompositeToolConfiguration(new FileStorageProperties(root))
                        .tools(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                                .getBeanProvider(com.mindrails.backend.tool.IdentifiedTool.class), TestMetrics.disabled()))
                .withBean(com.mindrails.backend.history.ChatHistory.class,
                        com.mindrails.backend.history.TestHistory::disabled)
                .withBean(com.mindrails.backend.metrics.MetricsRecorder.class, TestMetrics::disabled)
                .withBean(com.mindrails.backend.mcp.api.toolspec.McpTools.class, TestLlmClients::noMcpTools)
                .withBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class,
                        TestLlmClients::disabledKnowledgeTools)
                .withPropertyValues("mindrails.file-storage.directory=" + root)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                    var factory = context.getBean(LlmClientFactory.class);
                    var service = new com.mindrails.backend.agent.service.internal.DefaultSessionService(sessions);
                    org.springframework.test.util.ReflectionTestUtils.setField(service, "deliverables", files);
                    var loop = new com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService(
                            sessions, factory, com.mindrails.backend.history.TestHistory.disabled(), TestMetrics.disabled(), null);
                    try {
                        assertInstanceOf(com.mindrails.backend.common.ServiceResult.Success.class,
                                com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loop, a, "Generate CSV"));
                    } finally { loop.shutdown(); }
                    assertEquals(2, requests.size());
                    assertEquals(Set.of("write", "edit", "bash", "powershell", "read", "ls", "grep", "find"),
                            requests.getFirst().parameters().toolSpecifications().stream()
                                    .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                                    .collect(java.util.stream.Collectors.toSet()));
                    assertTrue(requests.getLast().messages().stream()
                            .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                            .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                            .anyMatch(message -> message.toolName().equals("read")
                                    && message.text().contains(
                                                                                    "Original read"
                                                                                        + " tool"
                                                                                        + " marker")));
                    assertTrue(((dev.langchain4j.data.message.SystemMessage) requests.getFirst().messages().getFirst()).text().contains("downloadUrl"));
                    assertTrue(requests.getLast().messages().stream().filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                            .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast).anyMatch(message -> message.text().contains("/sessions/" + a)));
                    Path output = files.download(a, files.list(a).getFirst().id());
                    assertInstanceOf(com.mindrails.backend.common.ServiceResult.Success.class, service.deleteSession(a));
                    assertFalse(Files.exists(output)); assertTrue(sessions.find(a).isEmpty());
                    assertThrows(AssetException.class, () -> files.list(a));
                });
    }

    @Test void cancellationDoesNotReplaceAPublishedFileAndDisabledStorageStillCleansIt() throws Exception {
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        sessions.insertIfAbsent(Session.builder().id(a).build());
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        var files = new SessionDeliverables(properties, sessions);
        var write = new WriteTool(root).buildTools(TestMetrics.disabled()).getFirst();
        call(files, a, write, Map.of("path", "report.txt", "content", "saved"));
        var output = files.download(a, files.list(a).getFirst().id());
        var cancellation = java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
        var context = InvocationContext.builder().chatMemoryId(a).invocationParameters(dev.langchain4j.invocation.InvocationParameters.from(
                Map.of(com.mindrails.backend.agent.model.LlmClient.CANCELLATION, cancellation))).build();
        assertThrows(java.util.concurrent.CancellationException.class, () -> new SessionDeliverableToolAdapter(files).adapt(write, a).toolExecutor().executeWithContext(
                ToolExecutionRequest.builder().id("cancelled-write").name("write").arguments("{\"path\":\"report.txt\",\"content\":\"cancelled\"}").build(), context));
        assertEquals("saved", Files.readString(output));
        assertEquals(1, files.list(a).size());
        files.deleteSession(a);
        assertFalse(Files.exists(root.resolve("assets/sessions").resolve(a.toString())));
    }

}
