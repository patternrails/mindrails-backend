package com.mindrails.backend.knowledge.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("mindrails.knowledge")
public class KnowledgeProperties {
    @Min(1024)
    @Max(104857600)
    private long maxUploadBytes = 26214400;

    @Min(1000)
    @Max(10000000)
    private int maxTextChars = 2000000;

    @Min(256)
    @Max(6000)
    private int chunkChars = 1200;

    @Min(0)
    @Max(1000)
    private int chunkOverlap = 160;

    @Min(1)
    @Max(8)
    private int workers = 2;

    @Min(1)
    @Max(10)
    private int maxAttempts = 3;

    @Min(10000)
    @Max(3600000)
    private int processingTimeoutMillis = 300000;

    @Min(1)
    @Max(30)
    private int maxResults = 6;

    @Min(512)
    @Max(100000)
    private int maxContextChars = 10000;

    @Min(512)
    @Max(150000)
    private int combinedContextChars = 16000;

    @DecimalMin("-1")
    @DecimalMax("1")
    private double minScore = 0.3;


    @Min(100)
    @Max(200000)
    private int toolMaxChars = 30000;

    @Min(1)
    @Max(5000)
    private int maxDocuments = 1000;

    @Min(1)
    @Max(50000)
    private int maxChunks = 20000;
}
