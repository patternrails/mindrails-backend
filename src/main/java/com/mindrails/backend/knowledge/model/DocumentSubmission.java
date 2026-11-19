package com.mindrails.backend.knowledge.model;

import java.util.UUID;

public record DocumentSubmission(UUID documentId, UUID jobId, String state, boolean unchanged) {}
