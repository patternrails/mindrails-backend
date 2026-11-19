package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.model.DocumentBlock;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.util.ArrayList;
import java.util.List;

/** Per-document budget shared across every page, paragraph and table. */
final class DocumentTextCollector {
    private final long limit;
    private final List<DocumentBlock> blocks = new ArrayList<>();
    private long characters;

    DocumentTextCollector(long limit) {
        this.limit = limit;
    }

    void add(String text, String location) {
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.isBlank()) {
            return;
        }
        characters += normalized.length();
        if (characters > limit) {
            throw KnowledgeException.invalid("Extracted text exceeds limit");
        }
        blocks.add(new DocumentBlock(normalized, location));
    }

    List<DocumentBlock> finish() {
        if (blocks.isEmpty()) {
            throw KnowledgeException.invalid("Document contains no usable text");
        }
        return List.copyOf(blocks);
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }
}
