package com.mindrails.backend.embedding;

import java.util.List;

public interface EmbeddingProvider {
    List<Double> embed(String text);
    default List<List<Double>> embedAll(List<String> texts) { return texts.stream().map(this::embed).toList(); }
    default String modelName() { return "unknown"; }
    int dimensions();
    String fingerprint();
}
