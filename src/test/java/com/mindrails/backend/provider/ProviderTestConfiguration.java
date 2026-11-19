package com.mindrails.backend.provider;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;

@TestConfiguration(proxyBeanMethods = false)
@Import(ProvidersConfiguration.class)
public class ProviderTestConfiguration {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    static ManagedProviderRegistry.Catalog fixture(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return MAPPER.readValue(input, ManagedProviderRegistry.Catalog.class);
        }
    }

    @Bean
    ObjectMapper objectMapper() {
        return MAPPER;
    }

    @Bean
    JdbcTemplate persistenceJdbcTemplate() throws IOException {
        var catalog = fixture("providers/catalog-test.json");
        var rows = new ArrayList<>(catalog.providers());
        var selected = rows.stream().filter(d -> d.name().equals(catalog.defaultProvider()))
                .findFirst().orElseThrow();
        rows.remove(selected);
        rows.addFirst(selected);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ProviderDefinition>>any()))
                .thenReturn(rows);
        return jdbc;
    }
}
