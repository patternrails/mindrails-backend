package com.mindrails.backend.memory.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("mindrails.agent-memory")
public class AgentMemoryProperties {
    @Min(1)
    @Max(10000)
    private int maxEntryChars = 2000;

    @Min(1)
    @Max(10000)
    private int maxEntries = 1000;

    @Valid @NotNull private Retrieval retrieval = new Retrieval();
    @Valid @NotNull private Qdrant qdrant = new Qdrant();

    @Data
    public static class Qdrant {
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,100}")
        @NotNull
        private String collection = "agent_memory";
    }

    @Data
    public static class Retrieval {
        @Min(1)
        @Max(100)
        private int maxResults = 5;

        @Min(512)
        @Max(100000)
        private int maxContextChars = 6000;

        @jakarta.validation.constraints.DecimalMin("-1.0")
        @jakarta.validation.constraints.DecimalMax("1.0")
        private double minScore = 0.3;
    }
}
