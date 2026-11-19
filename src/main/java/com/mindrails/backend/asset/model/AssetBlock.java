package com.mindrails.backend.asset.model;

import java.util.UUID;

public record AssetBlock(UUID id, int index, String text, String location) {}
