package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.identity.*;
import static org.mockito.Mockito.mock;
import com.mindrails.backend.agent.api.web.AgentTaskController;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.*;
import com.mindrails.backend.agent.service.internal.*;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.history.*;
import com.mindrails.backend.memory.*;
import com.mindrails.backend.metrics.*;
import com.mindrails.backend.provider.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes=AgentTaskApiIntegrationTest.App.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(15)
@TestPropertySource(properties = {"mindrails.identity.admin-password=test-admin-password-2026",
        "mindrails.identity.secure-cookie=false"})
class AgentTaskApiIntegrationTest {
    static final UUID AGENT=UUID.randomUUID(),OTHER=UUID.randomUUID(),PROVIDER=UUID.randomUUID();
    static final JsonMapper JSON=JsonMapper.builder().build();
    @Autowired ApplicationContext context;
    @org.springframework.test.context.bean.override.convention.TestBean
    TaskRegistry taskRegistry;
    @org.springframework.test.context.bean.override.convention.TestBean
    TaskExecutionStore taskExecutionStore;

    static TaskRegistry taskRegistry() { return new InMemoryTaskRegistry(App.PROVIDERS); }
    static TaskExecutionStore taskExecutionStore() { return new InMemoryTaskExecutionStore(); }
    String accessToken() {
        return context.getBean(IdentityService.class).login("admin","test-admin-password-2026","test").accessToken();
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({TestIdentityConfiguration.class,TaskRegistryConfiguration.class,DefaultTaskService.class,DefaultTaskExecutionService.class,
            DefaultAgentService.class,DefaultAgentTaskExecutionStreamGateway.class,CronjobManager.class,
            AgentTaskController.class,AgentTaskWebSocketConfiguration.class,ServiceResponseAdvice.class,ProviderService.class})
    static class App {
        @Bean AgentRegistry agents() {
            var agents=new AgentRegistryConfiguration().agentRegistry();
            for(UUID id:List.of(AGENT,OTHER))agents.insertIfAbsent(Agent.builder().id(id).name("test").createdAt(Instant.now()).build());return agents;
        }
        @Bean ProviderRegistry providers() { return PROVIDERS; }

        private static final ProviderRegistry PROVIDERS = providerCatalog();

        static ProviderRegistry providerCatalog() {
            return new SnapshotProviderRegistry(List.of(Provider.builder().id(PROVIDER).name("scripted").streaming(true)
                    .streamingChatModel(new StreamingChatModel() {
                        public void doChat(ChatRequest request,StreamingChatResponseHandler handler) {
                            handler.onPartialResponse("Hello task");handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello task")).build());
                        }
                    }).build()));
        }

            @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean LlmClientFactory factory() { return TestMemory.factory(List.of()); }
        @Bean ChatHistory history() { return TestHistory.disabled(); }
        @Bean MetricsRecorder metrics() { return TestMetrics.disabled(); }
    }
    String path(UUID task) { return "/api/agent/"+AGENT+"/tasks"+(task==null?"":"/"+task); }
    URI uri(String scheme,String path) { return URI.create(scheme+"://localhost:"+context.getEnvironment().getProperty("local.server.port")+path); }
    HttpResponse<String> http(HttpClient client,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(uri("http",path)).header("Content-Type","application/json").header("Authorization","Bearer "+accessToken())
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    UUID create(HttpClient client) throws Exception {
        var response=http(client,"POST",path(null),Map.of("providerId",PROVIDER,"instructions","Say hello","name","greet"));
        assertEquals(201,response.statusCode(),response.body());return JSON.readValue(response.body(),UUID.class);
    }
    @Test void realRestCrudAndStreamingProviderExecution() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            assertEquals(400,http(client,"POST",path(null),Map.of("providerId",PROVIDER,"instructions"," ")).statusCode());
            UUID task=create(client);
            try {
                assertEquals(com.mindrails.backend.common.ServiceResult.ErrorCode.CONFLICT,
                        assertInstanceOf(com.mindrails.backend.common.ServiceResult.Failure.class,context.getBean(ProviderService.class).delete(PROVIDER)).code());
                assertEquals(200,http(client,"GET",path(task),null).statusCode());
                assertEquals("{\"activeExecution\":null}",http(client,"GET",path(task)+"/executions/active",null).body());
                assertEquals(404,http(client,"GET","/api/agent/"+OTHER+"/tasks/"+task,null).statusCode());
                assertEquals(404,http(client,"GET","/api/agent/"+OTHER+"/tasks/"+task+"/executions/active",null).statusCode());
                var reply=http(client,"POST",path(task)+"/execute/details",Map.of("parameters",Map.of("day","today")));
                assertEquals(200,reply.statusCode(),reply.body());var execution=JSON.readTree(reply.body()).path("executionId").asString();
                var detail=http(client,"GET",path(task)+"/executions/"+execution,null);assertEquals(200,detail.statusCode());
                assertEquals("COMPLETED",JSON.readTree(detail.body()).path("status").asString());
                assertTrue(JSON.readTree(detail.body()).path("records").size()>0);
                assertEquals(400,http(client,"GET",path(task)+"/executions?size=0",null).statusCode());
                assertEquals(204,http(client,"PUT",path(task),Map.of("providerId",PROVIDER,"instructions","Changed","cronjob","0 9 * * 1-5")).statusCode());
            } finally { assertEquals(204,http(client,"DELETE",path(task),null).statusCode()); }
        }
    }
    @Test void webSocketHasExecutionIdentityOrderedEventsAndCanExecuteAgain() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            UUID task=create(client);var frames=new Frames();WebSocket ws=null;
            try {
                ws=client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken()).buildAsync(uri("ws",path(task)+"/stream"),frames).get(3,TimeUnit.SECONDS);
                var ready=frames.next();assertEquals("connection.ready",ready.path("type").asString());long seq=ready.path("seq").longValue();
                var ids=new HashSet<String>();
                for(int i=0;i<2;i++) {
                    UUID request=UUID.randomUUID();ws.sendText(JSON.writeValueAsString(Map.of("version",1,"type","execute","requestId",request,"data",Map.of("parameters",Map.of("i",i)))),true).get(3,TimeUnit.SECONDS);
                    var types=new ArrayList<String>();String execution=null;
                    while(true) {
                        var frame=frames.next();String type=frame.path("type").asString();types.add(type);
                        assertEquals(++seq,frame.path("seq").longValue());assertEquals(request.toString(),frame.path("requestId").asString());
                        assertEquals(task.toString(),frame.path("taskId").asString());assertFalse(frame.has("sessionId"));
                        if(execution==null)execution=frame.path("executionId").asString();assertEquals(execution,frame.path("executionId").asString());
                        if(type.equals("execution.completed"))break;
                    }
                    assertEquals("execution.accepted",types.getFirst());assertTrue(types.contains("answer.delta"));assertTrue(ids.add(execution));
                }
                ws.sendText("{\"version\":1,\"type\":\"execute\",\"requestId\":\""+UUID.randomUUID()+"\",\"data\":{\"parameters\":[]}}",true).get(3,TimeUnit.SECONDS);
                assertEquals("command.error",frames.next().path("type").asString());
            } finally {
                if(ws!=null)ws.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(3,TimeUnit.SECONDS);
                http(client,"DELETE",path(task),null);
            }
        }
    }
    @Test void webSocketHandshakeRejectsCrossAgentScope() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            UUID task=create(client);
            try {
                var error=assertThrows(ExecutionException.class,() -> client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken()).buildAsync(uri("ws","/api/agent/"+OTHER+"/tasks/"+task+"/stream"),new Frames()).get(3,TimeUnit.SECONDS));
                assertEquals(404,assertInstanceOf(WebSocketHandshakeException.class,error.getCause()).getResponse().statusCode());
            } finally { http(client,"DELETE",path(task),null); }
        }
    }
    static class Frames implements WebSocket.Listener {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();final StringBuilder current=new StringBuilder();
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last) {
            current.append(text);if(last) { frames.add(current.toString());current.setLength(0); }ws.request(1);return CompletableFuture.completedFuture(null);
        }
        JsonNode next() throws Exception { String text=frames.poll(4,TimeUnit.SECONDS);assertNotNull(text,"Expected a Task WS frame");return JSON.readTree(text); }
    }
}
