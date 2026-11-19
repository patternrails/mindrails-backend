package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.DocumentBlock;
import com.mindrails.backend.knowledge.model.DocumentChunk;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DocumentChunker {
    private final KnowledgeProperties properties;

    public DocumentChunker(KnowledgeProperties properties) {
        this.properties = properties;
    }

    public List<DocumentChunk> split(
            UUID revision, List<DocumentBlock> blocks, int size, int overlap) {
        if (size < 2 || overlap < 0 || overlap >= size) {
            throw KnowledgeException.invalid("Chunk overlap must be smaller than chunk size");
        }
        var result = new ArrayList<DocumentChunk>();
        for (var block : blocks) {
            for (int start = 0; start < block.text().length(); ) {
                int end = Math.min(start + size, block.text().length());
                if (end < block.text().length()) {
                    int boundary = block.text().lastIndexOf('\n', end);
                    if (boundary > start + size / 2) {
                        end = boundary + 1;
                    }
                    if (end > start && Character.isHighSurrogate(block.text().charAt(end - 1))) {
                        end--;
                    }
                }
                String text = block.text().substring(start, end).strip();
                if (!text.isBlank()) {
                    int index = result.size();
                    var id =
                            UUID.nameUUIDFromBytes(
                                    (revision + ":" + index + ":" + text)
                                            .getBytes(StandardCharsets.UTF_8));
                    result.add(new DocumentChunk(id, text, block.location(), index));
                    if (result.size() > properties.getMaxChunks()) {
                        throw KnowledgeException.invalid("Document exceeds chunk limit");
                    }
                }
                if (end == block.text().length()) {
                    break;
                }
                start = Math.max(start + 1, end - overlap);
                if (start > 0 && Character.isLowSurrogate(block.text().charAt(start))) {
                    start++;
                }
            }
        }
        return List.copyOf(result);
    }
}
