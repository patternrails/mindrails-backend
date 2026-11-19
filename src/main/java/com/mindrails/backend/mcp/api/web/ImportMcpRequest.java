package com.mindrails.backend.mcp.api.web;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ImportMcpRequest {
    @NotNull private Object config;
    private String mode = "CREATE_ONLY";
    private Map<String, Long> expectedVersions;
}
