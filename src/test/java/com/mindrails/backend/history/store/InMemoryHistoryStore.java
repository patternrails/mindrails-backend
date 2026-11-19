package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.model.HistoryPage;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.history.model.HistorySummary;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Development/test archive shared by the recorder and management queries; lost on process exit. */
public final class InMemoryHistoryStore implements HistoryStore, HistoryQueryStore, HistoryForkStore {
    private final ConcurrentHashMap<UUID, HistoryRecord> records = new ConcurrentHashMap<>();

    @Override
    public void insert(HistoryRecord record) {
        records.compute(record.id(), (id, previous) ->
                HistoryRecords.merge(previous, record));
    }

    @Override
    public java.util.List<HistoryRecord> session(UUID sessionId) {
        return records.values().stream()
                .filter(r -> sessionId.equals(r.sessionId()))
                .filter(r -> com.mindrails.backend.identity.ResourceAccess.visible(r.ownerId()))
                .sorted(Comparator.comparing(HistoryRecord::startedAt).thenComparing(HistoryRecord::id))
                .toList();
    }

    @Override
    public void insertFork(HistoryRecord record) {
        com.mindrails.backend.identity.ResourceAccess.requireOwner(record.ownerId());
        if (records.putIfAbsent(record.id(), record) != null) throw new IllegalStateException("History ID already exists");
    }

    @Override
    public void deleteFork(UUID id) { delete(id); }

    @Override
    public HistoryPage list(HistoryFilter filter, int page, int size) {
        var values =
                records.values().stream().filter(r -> com.mindrails.backend.identity.ResourceAccess.visible(r.ownerId()))
                        .filter(r -> r.status() != HistoryRecord.Status.RUNNING)
                        .filter(
                                r ->
                                        filter.matches(
                                                r.startedAt(),
                                                r.agentId(),
                                                r.sessionId(),
                                                r.status().name()))
                        .sorted(
                                Comparator.comparing(HistoryRecord::startedAt)
                                        .reversed()
                                        .thenComparing(
                                                r -> r.id().toString(), Comparator.reverseOrder()))
                        .toList();
        var items =
                values.stream()
                        .skip((long) page * size)
                        .limit(Math.min(size, 1000L - (long) page * size))
                        .map(HistorySummary::from)
                        .toList();
        return HistoryQueryStore.page(items, values.size(), page, size);
    }

    @Override
    public Optional<HistoryRecord> find(UUID id) {
        return Optional.ofNullable(records.get(id)).filter(r -> com.mindrails.backend.identity.ResourceAccess.visible(r.ownerId()));
    }

    @Override
    public boolean delete(UUID id) {
        return find(id).isPresent() && records.remove(id) != null;
    }
}
