package com.mindrails.backend.mcp.service.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("mindrails.mcp")
public class McpProperties {
    private boolean stdioEnabled = true;
    private String encryptionKey = "";

    @Min(1)
    @Max(50)
    private int maxServers = 50;

    @Min(1)
    @Max(200)
    private int maxTools = 200;

    @Min(1000)
    @Max(1048576)
    private int maxResultChars = 32000;

    @Min(1000)
    @Max(60000)
    private long preparationTimeoutMs = 15000;

    @Min(1000)
    @Max(30000)
    private long discoveryTimeoutMs = 10000;

    @Min(1000)
    private long catalogTtlMs = 60000;

    @Min(10000)
    private long idleTimeoutMs = 300000;
}
