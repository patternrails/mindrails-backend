package com.mindrails.backend.history;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.HistoryStore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stateless recording boundary for a chat's captured identity and messages. */
@Slf4j
@Component("chatHistory")
public final class ChatHistory {
    private final HistoryStore historyStore;
    private final AgentRegistry agentRegistry;

    public ChatHistory(HistoryStore historyStore,
            AgentRegistry agentRegistry) {
        this.historyStore = historyStore;
        this.agentRegistry = agentRegistry;
    }

    public void record(HistoryRecord record) {
        if (record == null) return;
        try {
            if (record.ownerId() == null && record.agentId() != null && agentRegistry != null
                    && record.invocationId() == null && !"[]".equals(record.messages())) {
                record = record.toBuilder().ownerId(agentRegistry.findManaged(record.agentId())
                        .map(Agent::getOwnerId).orElse(null)).build();
            }
            historyStore.insert(record);
        } catch (Exception failure) {
            log.warn("Chat history recording failed: {}", failure.getClass().getSimpleName());
        }
    }
}
