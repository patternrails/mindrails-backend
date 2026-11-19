package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.model.HistoryPage;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistorySummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HistoryQueryStore {
    HistoryPage list(HistoryFilter filter, int page, int size);

    Optional<HistoryRecord> find(UUID id);

    default Optional<String> metadata(UUID id) {
        return find(id).map(HistoryRecord::metadata);
    }

    default Optional<String> originalMessage(UUID id, int index) {
        if (index < 0) return Optional.empty();
        return find(id).flatMap(record -> {
            var messages = tools.jackson.databind.json.JsonMapper.builder().build().readTree(record.messages());
            return index < messages.size() ? Optional.of(messages.get(index).toString()) : Optional.empty();
        });
    }

    boolean delete(UUID id);

    static HistoryPage page(List<HistorySummary> items, long total, int page, int size) {
        long visible = Math.min(1000, total);
        return new HistoryPage(
                items, total, visible, 1000, page, size, (long) (page + 1) * size < visible);
    }
}
