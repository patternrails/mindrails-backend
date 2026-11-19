package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.embedding.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.StandardEnvironment;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

class MysqlProviderCredentialsIntegrationTest {
    @Test
    void encryptsBothCatalogsAndRestoresUpdatedKeysWithoutEnvironmentApiKeys()
            throws Exception {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(
                    System.getenv()
                            .getOrDefault(
                                    "MINDRAILS_MYSQL_URL",
                                    "jdbc:mysql://127.0.0.1:3306/mindrails"));
            source.setUsername(
                    System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
            source.setPassword(
                    System.getenv("MINDRAILS_MYSQL_PASSWORD"));
            var jdbc = new JdbcTemplate(source);
            var env = new StandardEnvironment();
            var mapper = JsonMapper.builder().build();
            var language = new MysqlProviderRegistry(jdbc, env, new ProvidersConfiguration());
            var id = UUID.randomUUID();
            var name = "credential-test-" + id;
            var definition =
                    new ProviderDefinition(
                            id,
                            name,
                            "OpenAiCompiable",
                            true,
                            "http://localhost:9000/v1",
                            "mysql-private-key",
                            "model",
                            null,
                            null,
                            null,
                            null,
                            null);
            try (var embeddings = new EmbeddingProviderRegistry(mapper, env, jdbc)) {
                assertEquals(jdbc.queryForList("SELECT name FROM embedding_providers WHERE is_default=TRUE", String.class),
                        embeddings.defaultProviderName().stream().toList());
                assertEquals(embeddings.defaultProviderName().stream().toList(),
                        embeddings.list().stream().filter(EmbeddingProviderRegistry.Info::defaultProvider)
                                .map(EmbeddingProviderRegistry.Info::name).toList());
                try {
                    var service = new ProviderService(language);
                    assertInstanceOf(
                            ServiceResult.Success.class, service.save(id, definition, false, true));
                    String encrypted =
                            jdbc.queryForObject(
                                    "SELECT api_key FROM providers WHERE id=?",
                                    String.class,
                                    id.toString());
                    assertTrue(encrypted.startsWith("enc:v1:"));
                    assertFalse(encrypted.contains("mysql-private-key"));
                    assertEquals(
                            "mysql-private-key",
                            new MysqlProviderRegistry(jdbc, env, new ProvidersConfiguration())
                                    .find(id)
                                    .orElseThrow()
                                    .getApiKey());
                    assertInstanceOf(
                            ServiceResult.Success.class,
                            service.save(id, definition.withApiKey(null), false, false));
                    assertInstanceOf(
                            ServiceResult.Success.class,
                            service.save(id, definition.withApiKey("rotated-key"), false, false));
                    assertEquals(
                            "rotated-key",
                            new MysqlProviderRegistry(jdbc, env, new ProvidersConfiguration())
                                    .find(id)
                                    .orElseThrow()
                                    .getApiKey());
                    embeddings.save(
                            new EmbeddingProviderDefinition(
                                    name,
                                    "openai",
                                    "embed-model",
                                    "http://localhost:9000/v1",
                                    "embedding-private-key",
                                    3,
                                    1000,
                                    0));
                    String embeddingKey =
                            jdbc.queryForObject(
                                    "SELECT api_key FROM embedding_providers WHERE name=?",
                                    String.class,
                                    name);
                    assertTrue(embeddingKey.startsWith("enc:v1:"));
                    assertFalse(embeddingKey.contains("embedding-private-key"));
                    try (var restored =
                            new EmbeddingProviderRegistry(mapper, env, jdbc)) {
                        assertEquals(
                                "embedding-private-key",
                                restored.definition(name).orElseThrow().apiKey());
                        assertTrue(
                                restored.list().stream()
                                        .filter(p -> p.name().equals(name))
                                        .findFirst()
                                        .orElseThrow()
                                        .hasApiKey());
                    }
                    assertFalse(
                            mapper.writeValueAsString(embeddings.list())
                                    .contains("embedding-private-key"));
                } finally {
                    language.delete(id);
                    embeddings.delete(name);
                }
            }
        }
    }
}
