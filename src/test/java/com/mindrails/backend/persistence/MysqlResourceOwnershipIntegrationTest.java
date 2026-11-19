package com.mindrails.backend.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.model.*;
import com.mindrails.backend.history.store.*;
import com.mindrails.backend.identity.*;
import com.mindrails.backend.knowledge.model.*;
import com.mindrails.backend.knowledge.registry.*;
import com.mindrails.backend.knowledge.service.*;
import com.mindrails.backend.mcp.model.*;
import com.mindrails.backend.mcp.registry.*;
import com.mindrails.backend.metrics.*;
import com.mindrails.backend.observability.*;
import com.mindrails.backend.provider.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class MysqlResourceOwnershipIntegrationTest {
    final UUID yuchen=UUID.randomUUID(),rex=UUID.randomUUID();
    final HikariDataSource source=new HikariDataSource();
    final JdbcTemplate jdbc;
    final JsonMapper json=new JsonMapper();
    final Provider provider=Provider.builder().id(UUID.randomUUID()).name("Global provider").modelName("test").streaming(false).build();
    final SnapshotProviderRegistry providers=new SnapshotProviderRegistry(List.of(provider)) {
        @Override public Optional<Provider> find(UUID id) { return Optional.of(provider.toBuilder().id(id).build()); }
    };
    MysqlResourceOwnershipIntegrationTest() {
        source.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL","jdbc:mysql://127.0.0.1:3306/mindrails"));
        source.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME","mindrails_app"));
        source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone","UTC"); source.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
        jdbc=new JdbcTemplate(source);
    }
    @AfterEach void cleanup() {
        try {
            for(var owner:List.of(yuchen,rex)) {
                for(var table:List.of("sessions","tasks","agents","mcp_servers","knowledge_bases","history","metrics_runs"))
                    jdbc.update("DELETE FROM "+table+" WHERE owner_id=?",owner.toString());
            }
        } finally { source.close(); }
    }
    Agent agent(UUID owner) {
        var now=Instant.now(); return Agent.builder().id(UUID.randomUUID()).ownerId(owner).name("Private").createdAt(now).updatedAt(now).build();
    }
    KnowledgeBase base(UUID owner) {
        var now=Instant.now();return KnowledgeBase.builder().id(UUID.randomUUID()).ownerId(owner).name("Private docs").description("").state(KnowledgeBaseState.ACTIVE).createdAt(now).updatedAt(now).build();
    }
    McpServer server(UUID owner) {
        var now=Instant.now();return new McpServer(UUID.randomUUID(),owner,"private-"+yuchen,new McpConnectionConfig("stdio","echo",List.of(),Map.of(),null,null,Map.of(),1000,null,Map.of()),false,0,now,now);
    }
    @Test void sqlScopesBeforePaginationAndInheritsChildOwnershipAndChecksKnowledgeBindings() {
        var sessions=new MysqlSessionRegistry(jdbc,json,providers);var tasks=new MysqlTaskRegistry(jdbc,json,providers);
        var agents=new MysqlAgentRegistry(jdbc,json,sessions,tasks);var bases=new MysqlKnowledgeRegistry(jdbc,json);var mcp=new MysqlMcpRegistry(jdbc);
        var y=agent(yuchen);var r=agent(rex);agents.insertIfAbsent(y);agents.insertIfAbsent(r);
        var now=Instant.now();var s=Session.builder().id(UUID.randomUUID()).parentAgentId(y.getId()).memoryId(UUID.randomUUID()).provider(provider).createdAt(now).updatedAt(now).build();
        var t=Task.builder().id(UUID.randomUUID()).parentAgentId(y.getId()).provider(provider).instructions("Private instructions").createdAt(now).updatedAt(now).build();
        sessions.insertIfAbsent(s);tasks.insertIfAbsent(t);assertEquals(yuchen,s.getOwnerId());assertEquals(yuchen,t.getOwnerId());
        var yb=base(yuchen);var rb=base(rex);bases.insert(yb);bases.insert(rb);
        var ym=server(yuchen);var rm=server(rex);
        try(var scope=ResourceAccess.as(yuchen)) { assertInstanceOf(ServiceResult.Success.class,mcp.saveAll(List.of(ym),Map.of(),100)); }
        try(var scope=ResourceAccess.as(rex)) { assertInstanceOf(ServiceResult.Success.class,mcp.saveAll(List.of(rm),Map.of(),100)); }
        try(var scope=ResourceAccess.as(yuchen)) {
            assertEquals(List.of(y.getId()),agents.listManaged(0,1).stream().map(Agent::getId).toList());assertTrue(agents.listManaged(1,1).isEmpty());
            assertTrue(agents.find(r.getId()).isEmpty());assertTrue(agents.mutate(r.getId(),a -> a.setName("hacked")).isEmpty());assertTrue(agents.remove(r.getId()).isEmpty());
            assertEquals(1,sessions.listManaged(y.getId(),0,10).size());assertTrue(tasks.list(r.getId(),0,10).isEmpty());
            assertEquals(List.of(yb.getId()),bases.listKnowledgeBases().stream().map(KnowledgeBase::getId).toList());
            assertThrows(KnowledgeException.class,() -> bases.getKnowledgeBase(rb.getId()));
            assertEquals(List.of(ym),mcp.list());assertTrue(mcp.find(rm.id()).isEmpty());assertInstanceOf(ServiceResult.Failure.class,mcp.remove(rm.id(),0));
            assertThrows(IdentityException.class,() -> sessions.insertIfAbsent(s.toBuilder().id(UUID.randomUUID()).ownerId(rex).memoryId(UUID.randomUUID()).build()));
        }
        var beanFactory=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("knowledge",bases));
        var binding=new DefaultKnowledgeBindingService(agents,sessions,beanFactory.getBeanProvider(KnowledgeRegistry.class));
        assertThrows(IdentityException.class,() -> binding.bind(y.getId(),List.of(rb.getId()),y.getVersion()),"Even server-side bindings must reject other owners");
        assertEquals(List.of(yb.getId()),binding.bind(y.getId(),List.of(yb.getId()),y.getVersion()).knowledgeBaseIds());
        assertEquals("Private",agents.findManaged(r.getId()).orElseThrow().getName());
    }
    @Test void archivesAndMetricsRetainOwnerAfterDeletingTheAgent() {
        var now=Instant.now();var history=new MysqlHistoryStore(jdbc);var query=new MysqlHistoryQueryStore(jdbc);
        var own=HistoryRecord.builder().id(UUID.randomUUID()).ownerId(yuchen).agentId(UUID.randomUUID()).sessionId(UUID.randomUUID()).memoryId(UUID.randomUUID()).transport(HistoryRecord.Transport.SYNC)
                .status(HistoryRecord.Status.COMPLETED).messages("[]").metadata("{}").startedAt(now).finishedAt(now).schemaVersion(1).build();
        var other=own.toBuilder().id(UUID.randomUUID()).ownerId(rex).build();history.insert(own);history.insert(other);
        var metrics=new MysqlMetricsRepository(jdbc,new MetricsProperties(),new MetricsHealth());
        var run=MetricsRecords.finish(LlmClient.parameters(Session.builder().id(own.sessionId()).ownerId(yuchen).parentAgentId(own.agentId()).provider(provider).memoryId(UUID.randomUUID()).build(),null,"REST"),"SUCCEEDED",null,null,null);
        metrics.save(run);metrics.save(run.toBuilder().runId(UUID.randomUUID()).ownerId(rex).sessionId(UUID.randomUUID()).build());
        try(var scope=ResourceAccess.as(yuchen)) {
            assertEquals(1,query.list(new HistoryFilter(null,null,null,null,null),0,1).items().size());
            assertTrue(query.find(other.id()).isEmpty());assertFalse(query.delete(other.id()));
            assertEquals(1,new MysqlMetricsQueryStore(jdbc).overview(new ArchiveFilter(null,null,null,null,null)).summary().runs());
            assertTrue(query.find(own.id()).isPresent());
        }
    }
}
