package com.mindrails.backend.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.InMemoryTaskExecutionStore;
import com.mindrails.backend.agent.service.internal.llm.*;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.spec.WriteTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TaskExecutionDeliverablesTest {
    @TempDir Path root;
    final InMemoryTaskExecutionStore store=new InMemoryTaskExecutionStore();
    UUID execution() {
        UUID id=UUID.randomUUID();store.insert(TaskExecution.builder().id(id).agentId(UUID.randomUUID()).taskId(UUID.randomUUID())
                .requestId(UUID.randomUUID()).createdAt(Instant.now()).status(TaskExecution.Status.RUNNING).build());return id;
    }
    TaskExecutionDeliverables files() {
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        return new TaskExecutionDeliverables(properties, store);
    }
    String write(TaskExecutionDeliverables files,UUID id,String name,String content,InvocationParameters parameters) {
        var tool=new TaskDeliverableToolAdapter(files).adapt(new WriteTool(root).buildTools(TestMetrics.disabled()).getFirst(),id);
        return tool.toolExecutor().executeWithContext(ToolExecutionRequest.builder().id("write").name("write")
                .arguments(new JsonMapper().writeValueAsString(Map.of("path",name,"content",content))).build(),
                InvocationContext.builder().chatMemoryId(id).invocationParameters(parameters).build()).resultText();
    }
    @Test void outputsAreExecutionScopedAndRemainDownloadableAfterCompletionAndServiceRestart() throws Exception {
        UUID a=execution(),b=execution();var files=files();
        assertTrue(write(files,a,"report.csv","first",InvocationParameters.from(Map.of())).contains("/task-executions/"+a));
        write(files,b,"report.csv","second",InvocationParameters.from(Map.of()));
        var result=files.list(a).getFirst();assertEquals("first",Files.readString(files.download(a,result.id())));
        assertEquals("second",Files.readString(files.download(b,files.list(b).getFirst().id())));
        store.save(store.find(a).orElseThrow().toBuilder().status(TaskExecution.Status.COMPLETED).build());
        assertEquals(result,files().list(a).getFirst());
        assertThrows(AssetException.class,() -> files.resolve(a,"../escape.csv"));
        assertThrows(AssetException.class,() -> files.list(UUID.randomUUID()));
    }
    @Test void cancellationDoesNotReplacePublishedOutput() throws Exception {
        UUID id=execution();var files=files();write(files,id,"report.txt","saved",InvocationParameters.from(Map.of()));
        var parameters=InvocationParameters.from(Map.of(LlmClient.CANCELLATION,CompletableFuture.completedFuture(null)));
        assertThrows(java.util.concurrent.CancellationException.class,() -> write(files,id,"report.txt","cancelled",parameters));
        assertEquals("saved",Files.readString(files.download(id,files.list(id).getFirst().id())));
    }
    @Test void taskFactoryBindsNativeFileToolsToTheExecutionDirectory() throws Exception {
        UUID id=execution();var files=files();var calls=new AtomicInteger();var requests=new ArrayList<ChatRequest>();
        ChatModel model=new ChatModel() { public ChatResponse doChat(ChatRequest request) {
            requests.add(request);return ChatResponse.builder().aiMessage(calls.incrementAndGet()==1
                    ?AiMessage.from(ToolExecutionRequest.builder().id("write-report").name("write").arguments("{\"path\":\"report.csv\",\"content\":\"real content\"}").build())
                    :AiMessage.from("[\"saved\"]")).build();
        } };
        var task=Task.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).instructions("Create a report")
                .provider(Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build()).build();
        var factory=TestLlmClients.withCapabilities(List.of(new WriteTool(root)),
                new SessionDeliverableToolAdapter(TestLlmClients.sessionFiles()), new TaskDeliverableToolAdapter(files));
        var result=factory.forTask(task,"Agent policy",id,mock(TaskExecutionObserver.class));
        var client=((com.mindrails.backend.common.ServiceResult.Success<LlmClient>)result).value();
        client.chatWithLines(id,"Create a report",LlmClient.parameters(task,id,UUID.randomUUID(),Instant.now(),"REST"));
        assertEquals(2,calls.get());assertEquals("real content",Files.readString(files.download(id,files.list(id).getFirst().id())));
        assertTrue(requests.getFirst().messages().toString().contains(files.outputDirectory(id).toString()));
        assertTrue(requests.getLast().messages().toString().contains("/task-executions/"+id));
    }
}
