package com.mindrails.backend.asset.model;

/** An immutable extraction block and the original version that produced it. */
public record AssetSource(AssetVersion version, AssetBlock block) {}
