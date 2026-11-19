package com.mindrails.backend.knowledge.model;

import java.util.List;
import java.util.function.Function;

public record KnowledgePage<T>(List<T> items, long total, int page, int size, boolean hasNext) {
    public <R> KnowledgePage<R> map(Function<T, R> mapper) {
        return new KnowledgePage<>(items.stream().map(mapper).toList(), total, page, size, hasNext);
    }
}
