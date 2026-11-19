package com.mindrails.backend.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mindrails.backend.agent.api.web.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.*;
import com.mindrails.backend.agent.service.internal.*;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.*;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.mcp.model.*;
import com.mindrails.backend.mcp.registry.*;
import com.mindrails.backend.provider.*;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.skill.SkillArchives;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.skill.api.SkillController;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ResourceIsolationTest {
    @TempDir Path directory;
    final JsonMapper json=new JsonMapper();
    final UUID providerId=UUID.randomUUID();
    IdentityService identity; String admin,yuchen,rex;
    AgentRegistry agents; SessionRegistry sessions; TaskRegistry tasks;
    AgentResourceService resources; SkillCatalog skills; InMemoryMcpRegistry mcp;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        var properties=new IdentityProperties(); properties.setAdminPassword("test-resource-isolation-2026");
        identity=new IdentityService(new InMemoryIdentityStore(),properties);
        admin=identity.login("admin",properties.getAdminPassword(),"local").accessToken();
        for(var name:List.of("yuchen","rex")) identity.create(identity.authenticate(admin),new UserController.UserWrite(name,name,"","",properties.getAdminPassword(),null,Identity.Role.Normal));
        yuchen=identity.login("yuchen",properties.getAdminPassword(),"local").accessToken();
        rex=identity.login("rex",properties.getAdminPassword(),"local").accessToken();
        agents=new AgentRegistryConfiguration().agentRegistry();
        ReflectionTestUtils.setField(identity,"resourceAgents",agents);
        sessions=new SessionRegistryConfiguration().sessionRegistry(); tasks=new InMemoryTaskRegistry();
        var providers=new SnapshotProviderRegistry(List.of(Provider.builder().id(providerId).name("Global").build()));
        var factory=mock(LlmClientFactory.class);
        var sessionService=new DefaultSessionService(sessions);
        var service=new DefaultAgentService(agents,sessionService,providers);
        var executions=new DefaultTaskExecutionService(agents,tasks,new InMemoryTaskExecutionStore(),factory,Runnable::run,TestHistory.disabled(),TestMetrics.disabled());
        var taskService=new DefaultTaskService(agents,tasks,providers,executions,event -> {});
        var skillFactory = new SkillFactory(new FileStorageProperties(directory));
        mcp=new InMemoryMcpRegistry(); skills=new SkillCatalog(skillFactory);
        resources=new AgentResourceService(agents,mcp,skills);
        ReflectionTestUtils.setField(service,"resources",resources);
        ReflectionTestUtils.setField(service,"taskService",taskService);
        ReflectionTestUtils.setField(taskService,"resources",resources);
        mvc=MockMvcBuilders.standaloneSetup(new AgentController(service),new AgentSessionController(service),new AgentTaskController(service,executions),
                new AgentResourceController(resources),new SkillController(skills, new SkillArchives(skillFactory, mock(org.springframework.jdbc.core.JdbcTemplate.class))))
                .setControllerAdvice(new IdentityErrorAdvice(),new ServiceResponseAdvice())
                .addFilters(new IdentityFilter(identity)).build();
    }
    UUID create(String token,String name) throws Exception {
        var response=mvc.perform(post("/api/agent").header("Authorization","Bearer "+token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("name",name,"ownerId",identity.authenticate(rex).id()))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readValue(response.getContentAsString(),UUID.class);
    }
    UUID postId(String token,String path,Object body) throws Exception {
        return json.readValue(mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),UUID.class);
    }
    @Test void authenticatedUsersOnlyReadAndMutateTheirOwnAgentsSessionsAndTasks() throws Exception {
        UUID a=create(yuchen,"Yuchen"), b=create(rex,"Rex");
        UUID session=postId(yuchen,"/api/agent/"+a+"/sessions",Map.of("providerId",providerId));
        UUID task=postId(yuchen,"/api/agent/"+a+"/tasks",Map.of("providerId",providerId,"instructions","Run","skillIds",List.of()));
        var owner=identity.authenticate(yuchen).id();
        assertEquals(owner,agents.findManaged(a).orElseThrow().getOwnerId());
        assertEquals(owner,sessions.findManaged(session).orElseThrow().getOwnerId());
        assertEquals(owner,tasks.find(task).orElseThrow().getOwnerId());
        mvc.perform(get("/api/agent?size=1").header("Authorization","Bearer "+yuchen)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(a.toString())).andExpect(jsonPath("$.length()").value(1));
        for(var token:List.of(rex,admin)) {
            for(var suffix:List.of("","/sessions","/tasks","/tasks/"+task,"/resources"))
                mvc.perform(get("/api/agent/"+a+suffix).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
            mvc.perform(post("/api/agent/"+a+"/sessions").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("providerId",providerId)))).andExpect(status().isNotFound());
            mvc.perform(delete("/api/agent/"+a+"/sessions/"+session).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
            mvc.perform(delete("/api/agent/"+a+"/tasks/"+task).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
            mvc.perform(delete("/api/agent/"+a).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/agent/"+b).header("Authorization","Bearer "+yuchen)).andExpect(status().isNotFound());
        assertNull(ResourceAccess.userId(),"Filter must restore the worker thread after each request");
    }
    UUID skill(UUID owner,String content) throws Exception {
        var path=directory.resolve("skill").resolve(owner.toString()).resolve("example");Files.createDirectories(path);
        Files.writeString(path.resolve("SKILL.md"),"---\nname: example\ndescription: Private test\n---\n"+content);
        return SkillCatalog.id(owner, skills.definitions(owner).getFirst().name());
    }
    McpServer server(UUID owner) {
        var now=Instant.now();return new McpServer(UUID.randomUUID(),owner,"same-name",new McpConnectionConfig("stdio","echo",List.of(),Map.of(),null,null,Map.of(),1000,null,Map.of()),false,0,now,now);
    }
    @Test void skillAndMcpCatalogsAndBindingsAreUserScopedIncludingSameNames() throws Exception {
        UUID a=create(yuchen,"Yuchen"); UUID y=identity.authenticate(yuchen).id(), r=identity.authenticate(rex).id();
        UUID ys=skill(y,"Yuchen instructions"), rs=skill(r,"Rex secret");
        var ym=server(y);var rm=server(r);
        assertInstanceOf(ServiceResult.Success.class,mcp.saveAll(List.of(ym,rm),Map.of(),100));
        try(var ignored=ResourceAccess.as(y)) {
            assertEquals(List.of(ym),mcp.list());assertTrue(mcp.find(rm.id()).isEmpty());
            var original=resources.get(a);
            assertThrows(IdentityException.class,() -> resources.bind(a,new AgentResourceService.Bindings(List.of(rs),List.of(),original.version())));
            assertThrows(IdentityException.class,() -> resources.bind(a,new AgentResourceService.Bindings(List.of(ys),List.of(rm.id()),original.version())));
            assertEquals(original,resources.get(a));
            assertEquals(List.of(ys),resources.bind(a,new AgentResourceService.Bindings(List.of(ys),List.of(ym.id()),original.version())).skillIds());
            assertThrows(IdentityException.class,() -> resources.bind(a,original),"Stale updates must not overwrite bindings");
        }
        mvc.perform(get("/api/skills/example/detail").header("Authorization","Bearer "+yuchen)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skill.ownerId").value(y.toString())).andExpect(jsonPath("$.content").value("Yuchen instructions"));
        mvc.perform(get("/api/skills/example/detail").header("Authorization","Bearer "+rex)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skill.ownerId").value(r.toString())).andExpect(jsonPath("$.content").value("Rex secret"));
        mvc.perform(get("/api/skills/example/detail").header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
    }
    @Test void capturedScopeSurvivesAsyncThreadsAndRestoresThePreviousIdentity() throws Exception {
        var owner=identity.authenticate(yuchen).id();
        Runnable work;
        try(var ignored=ResourceAccess.as(owner)) { work=ResourceAccess.capture(() -> assertEquals(owner,ResourceAccess.userId())); }
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            executor.submit(work).get(); assertNull(executor.submit(ResourceAccess::userId).get());
        }
        assertNull(ResourceAccess.userId());
    }
    @Test void websocketTicketsAndMessageCallbacksKeepTheAuthenticatedOwner() throws Exception {
        UUID a=create(yuchen,"Yuchen");
        UUID session=postId(yuchen,"/api/agent/"+a+"/sessions",Map.of("providerId",providerId));
        var path="/api/agent/"+a+"/sessions/"+session+"/stream";
        assertThrows(IdentityException.class,() -> identity.ticket(identity.authenticate(rex),path));
        assertNotNull(identity.ticket(identity.authenticate(yuchen),path));
        var socket=mock(org.springframework.web.socket.WebSocketSession.class);
        var current=identity.authenticate(yuchen);
        when(socket.getPrincipal()).thenReturn(current);when(socket.getId()).thenReturn("owned");
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        var delegate=mock(org.springframework.web.socket.WebSocketHandler.class);
        doAnswer(call -> { assertEquals(current.id(),ResourceAccess.userId());assertTrue(agents.findManaged(a).isPresent());return null; })
                .when(delegate).handleMessage(any(),any());
        var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            var handler=new IdentityWebSocketHandler(delegate,identity,timer);
            handler.afterConnectionEstablished(socket);
            handler.handleMessage(socket,new org.springframework.web.socket.TextMessage("{}"));
            verify(delegate).handleMessage(any(),any());
            handler.afterConnectionClosed(socket,org.springframework.web.socket.CloseStatus.NORMAL);
        } finally { timer.shutdownNow(); }
        assertNull(ResourceAccess.userId());
    }
    @Test void modelExecutorsResolvePermissionsAgainAfterPreparationOnAnotherThread() throws Exception {
        var owner=identity.authenticate(yuchen).id();
        var current=new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.service.tool.ToolProvider>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var specification=dev.langchain4j.agent.tool.ToolSpecification.builder().name("private_skill").description("Private").build();
        current.set(request -> dev.langchain4j.service.tool.ToolProviderResult.builder().add(specification,(call,id) -> {
            assertEquals(owner,ResourceAccess.userId());calls.incrementAndGet();return "private";
        }).build());
        var request=mock(dev.langchain4j.service.tool.ToolProviderRequest.class);
        var tool=OwnedTools.provider(owner,current::get).provideTools(request).tools().get(specification);
        var call=dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("private_skill").arguments("{}").build();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals("private",executor.submit(() -> tool.execute(call,null)).get());
            current.set(null);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,() -> executor.submit(() -> tool.execute(call,null)).get());
            assertInstanceOf(IdentityException.class,failure.getCause());
            assertNull(executor.submit(ResourceAccess::userId).get());
        }
        assertEquals(1,calls.get());
    }

    @Test void aBrokenSkillCannotBreakAnotherUsersCatalog() throws Exception {
        var y=identity.authenticate(yuchen).id();var r=identity.authenticate(rex).id();
        skill(y,"Yuchen instructions");skill(r,"Rex instructions");
        Files.writeString(directory.resolve("skill").resolve(r.toString()).resolve("example/SKILL.md"),"invalid front matter");
        mvc.perform(get("/api/skills/example/detail").header("Authorization","Bearer "+yuchen)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Yuchen instructions"));
    }

}
