package com.mindrails.backend.knowledge.model;

import java.util.UUID;

public record DocumentChunk(UUID id, String text, String location, int index) {}
