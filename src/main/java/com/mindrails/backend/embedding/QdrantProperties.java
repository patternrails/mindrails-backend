package com.mindrails.backend.embedding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("mindrails.qdrant")
public class QdrantProperties {
    @NotBlank private String host = "127.0.0.1";

    @Min(1)
    @Max(65535)
    private int port = 6333;

    private boolean tls;
    @lombok.ToString.Exclude private String apiKey = "";

    @Min(100)
    @Max(60000)
    private int timeoutMillis = 5000;
}
