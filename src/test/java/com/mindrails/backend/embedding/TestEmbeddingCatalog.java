package com.mindrails.backend.embedding;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class TestEmbeddingCatalog {
    private TestEmbeddingCatalog() {}

    public static EmbeddingProviderRegistry open(ObjectMapper mapper, EmbeddingProviderDefinition... definitions) {
        return new EmbeddingProviderRegistry(mapper, new StandardEnvironment(), database(definitions));
    }

    public static JdbcTemplate database(EmbeddingProviderDefinition... definitions) {
        var jdbc = mock(JdbcTemplate.class);
        var source = mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(source);
        try {
            when(source.getConnection()).thenAnswer(ignored -> mock(Connection.class));
        } catch (SQLException impossible) {
            throw new AssertionError(impossible);
        }
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<EmbeddingProviderDefinition>>any()))
                .thenReturn(List.of(definitions));
        return jdbc;
    }

    public static EmbeddingProviderRegistry qwen(ObjectMapper mapper, Environment env) throws IOException {
        try (var input = new ClassPathResource("embedding-providers-test.json").getInputStream()) {
            var fixture = mapper.readValue(input, Fixture.class);
            var qwen = fixture.providers().stream().filter(d -> "qwen-default".equals(d.name()))
                    .findFirst().orElseThrow().withApiKey(env.getRequiredProperty("QWEN_EMBEDDING_API_KEY"));
            return new EmbeddingProviderRegistry(mapper, env, database(qwen));
        }
    }

    private record Fixture(List<EmbeddingProviderDefinition> providers) {}
}
