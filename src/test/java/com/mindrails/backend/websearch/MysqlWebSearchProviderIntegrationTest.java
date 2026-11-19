package com.mindrails.backend.websearch;

import com.mindrails.backend.common.ServiceResponseException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MysqlWebSearchProviderIntegrationTest {
    private final String database = "mindrails_search_test_" + UUID.randomUUID().toString().replace("-", "");
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private WebSearchProviderRegistry registry;
    private WebSearchProviderService service;

    @BeforeEach
    void createCatalog() throws Exception {
        mysql("CREATE DATABASE " + database + "; GRANT ALL ON " + database + ".* TO 'mindrails_app'@'%';\n"
                + Files.readString(Path.of("db/schema.sql")).replace("USE mindrails;", "USE " + database + ";"));
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/" + database);
        source.setUsername("mindrails_app");
        source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        registry = newRegistry();
        service = new WebSearchProviderService(registry, org.mockito.Mockito.mock(WebSearchClient.class));
    }

    @AfterEach
    void dropCatalog() throws Exception {
        if (source != null) source.close();
        mysql("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void catalogMaintainsOneActiveProviderAcrossUpdatesSwitchesAndDeletion() {
        assertTrue(registry.activeConnection().isEmpty());
        var first = service.save(null, input("first", "private-test-key", null));
        var second = service.save(null, input("second", "other-test-key", null));
        assertTrue(first.active());
        assertFalse(second.active());
        assertTrue(jdbc.queryForObject("SELECT api_key FROM web_search_providers WHERE id=?",
                String.class, first.id().toString()).startsWith("enc:v1:"));
        var changed = service.save(first.id(), input("renamed", null, first.version()));
        assertEquals("private-test-key", registry.activeConnection().orElseThrow().apiKey());
        assertEquals(1, changed.version());
        assertThrows(ServiceResponseException.class, () -> service.save(first.id(), input("stale", null, first.version())));
        assertTrue(service.activate(second.id()).active());
        assertFalse(service.get(first.id()).active());
        assertEquals("other-test-key", registry.activeConnection().orElseThrow().apiKey());
        service.delete(second.id());
        assertEquals(first.id(), registry.activeConnection().orElseThrow().provider().id());
        service.delete(first.id());
        assertTrue(registry.activeConnection().isEmpty());
    }

    @Test
    void concurrentCreatesAgainstAnEmptyCatalogLeaveOneActiveProvider() throws Exception {
        var other = new WebSearchProviderService(newRegistry(), org.mockito.Mockito.mock(WebSearchClient.class));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                start.await();
                return service.save(null, input("first", "first-test-key", null));
            });
            var second = workers.submit(() -> {
                start.await();
                return other.save(null, input("second", "second-test-key", null));
            });
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS).active(), second.get(5, TimeUnit.SECONDS).active());
        }
        assertEquals(2, service.list(0, 20).size());
        assertEquals(1, service.list(0, 20).stream().filter(WebSearchProvider::active).count());
    }

    private WebSearchProviderRegistry newRegistry() {
        return new WebSearchProviderRegistry(jdbc, new DataSourceTransactionManager(source),
                new StandardEnvironment(), new JsonMapper());
    }

    private static WebSearchProvider.Input input(String name, String key, Long version) {
        return new WebSearchProvider.Input(name, WebSearchProvider.BOCHA, "https://api.bocha.cn/v1/web-search",
                key, null, Map.of(), 15000, version);
    }

    private static void mysql(String sql) throws Exception {
        var command = new ProcessBuilder("docker", "exec", "-i", "mindrails-mysql", "sh", "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --user=root --batch").redirectErrorStream(true).start();
        try (var input = command.getOutputStream()) {
            input.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, command.waitFor(), output);
    }
}
