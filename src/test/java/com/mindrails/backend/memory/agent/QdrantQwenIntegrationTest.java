package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.service.internal.TestSessionChat;
import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.embedding.QdrantProperties;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in: makes paid embedding calls using only synthetic test facts. */
@EnabledIfEnvironmentVariable(named = "MINDRAILS_TEST_QWEN_EMBEDDING", matches = "true")
@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class QdrantQwenIntegrationTest {
    @Test void realQwenEmbeddingsRecallChineseFactsWithoutKeywordOverlap() throws Exception {
        var mapper = JsonMapper.builder().build();
        var settings = new QdrantProperties();
        settings.setHost(System.getenv().getOrDefault("MINDRAILS_QDRANT_HOST", "127.0.0.1"));
        settings.setPort(Integer.parseInt(System.getenv().getOrDefault("MINDRAILS_QDRANT_PORT", "6333")));
        var collection = "test_qwen_" + UUID.randomUUID().toString().replace("-", "");
        try (var registry = com.mindrails.backend.embedding.TestEmbeddingCatalog.qwen(mapper, new StandardEnvironment());
             var store = new QdrantAgentMemoryStore(settings, collection, registry.require("qwen-default"), mapper)) {
            var f = new Fixture(store);
            UUID owner = f.agent(), other = f.agent();
            var database = success(f.memory.create(owner, input("project.storage", "订单和客户资料统一保存在 MySQL 关系型数据库中。", false, null)));
            success(f.memory.create(owner, input("food", "周末喜欢制作意大利面和番茄酱。", false, null)));
            success(f.memory.create(other, input("project.storage", "另一个项目使用 PostgreSQL 数据库。", false, null)));
            var hits = success(f.memory.searchForTool(owner, "业务系统采用哪一种持久化引擎？"));
            assertFalse(hits.isEmpty());
            assertEquals(database.id(), hits.getFirst().id());
            assertTrue(hits.stream().allMatch(entry -> entry.agentId().equals(owner)));
            UUID session = f.session(owner);
            success(TestSessionChat.chat(f.streams, session, "业务系统采用哪一种持久化引擎？"));
            assertTrue(lastUser(f.model.requests.getLast()).contains(database.content()));
        } finally {
            try (var http = new JsonHttpClient(mapper, 5000)) {
                http.request("DELETE", URI.create("http://" + settings.getHost() + ":" + settings.getPort() + "/collections/" + collection), null, Map.of()).requireSuccess();
            }
        }
    }
}
