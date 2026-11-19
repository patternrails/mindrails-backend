package com.mindrails.backend.memory;

import jakarta.validation.constraints.Min;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("mindrails.chat-memory")
public class ChatMemoryProperties {
    @Min(1)
    private int maxMessages = 100;
}
