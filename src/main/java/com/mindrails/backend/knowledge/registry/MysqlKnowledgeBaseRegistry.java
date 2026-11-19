package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MysqlKnowledgeBaseRegistry extends JdbcKnowledgeSnapshots
        implements KnowledgeBaseRegistry {
    public MysqlKnowledgeBaseRegistry(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    public KnowledgeBase getKnowledgeBase(UUID id) {
        return one("knowledge_bases", id, KnowledgeBase.class);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return query("knowledge_bases", " ORDER BY created_at DESC,id", KnowledgeBase.class);
    }

    public void lockKnowledgeBase(UUID id) {
        getKnowledgeBase(id);
        if (jdbc.queryForList(
                        "SELECT id FROM knowledge_bases WHERE id=? FOR UPDATE",
                        String.class,
                        id.toString())
                .isEmpty()) {
            throw KnowledgeException.missing();
        }
    }

    public void insert(KnowledgeBase e) {
        e.setOwnerId(com.mindrails.backend.identity.ResourceAccess.owner(e.getOwnerId()));
        com.mindrails.backend.identity.ResourceAccess.requireOwner(e.getOwnerId());
        insert("knowledge_bases", e, e.getState().name(), e.getCreatedAt(), Map.of("owner_id", e.getOwnerId().toString()));
    }

    public void save(KnowledgeBase e) {
        var stored = getKnowledgeBase(e.getId());
        if (!java.util.Objects.equals(stored.getOwnerId(), e.getOwnerId())) throw new IllegalArgumentException("Knowledge owner is immutable");
        save("knowledge_bases", e, e.getState().name());
    }

    public void deleteKnowledgeBase(UUID id) {
        getKnowledgeBase(id);
        jdbc.update("DELETE FROM knowledge_bases WHERE id=?", id.toString());
    }
}
