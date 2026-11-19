package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.util.List;

public final class KnowledgePages {
    private KnowledgePages() {}

    public static <T> KnowledgePage<T> page(List<T> items, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw KnowledgeException.invalid("page must be non-negative; size 1..100");
        }
        long offset = (long) page * size;
        return new KnowledgePage<>(
                items.stream().skip(offset).limit(size).toList(),
                items.size(),
                page,
                size,
                offset + size < items.size());
    }
}
