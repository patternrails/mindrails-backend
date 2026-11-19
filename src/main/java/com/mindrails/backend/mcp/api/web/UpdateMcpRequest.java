package com.mindrails.backend.mcp.api.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMcpRequest {
    @NotNull
    @Min(0)
    private Long version;

    private Object config;
    private Boolean enabled;
}
