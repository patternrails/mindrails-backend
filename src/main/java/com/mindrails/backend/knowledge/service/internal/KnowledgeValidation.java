package com.mindrails.backend.knowledge.service.internal;

import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.normalizeTags;

import com.mindrails.backend.knowledge.model.VersionedKnowledgeEntity;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.util.List;

public final class KnowledgeValidation {
    private KnowledgeValidation() {}

    public static void validName(String name) {
        if (name == null || name.isBlank() || name.length() > 160) {
            throw KnowledgeException.invalid("Name must contain 1..160 characters");
        }
    }

    public static void validateDoc(String key, String title, List<String> tags) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw KnowledgeException.invalid("Invalid document key");
        }
        validName(title);
        normalizeTags(tags);
    }

    public static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        if (tags.size() > 20
                || tags.stream()
                        .anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 64)) {
            throw KnowledgeException.invalid("Invalid document tags");
        }
        return tags.stream().map(String::strip).distinct().sorted().toList();
    }

    public static void checkVersion(VersionedKnowledgeEntity entity, long version) {
        if (entity.getVersion() != version) {
            throw KnowledgeException.conflict("Resource version changed");
        }
    }
}
