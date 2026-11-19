package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryRecord;
import java.util.List;
import java.util.UUID;

/** Raw archive snapshots, independent of recording settings and UI pagination. Writes must fail loudly. */
public interface HistoryForkStore {
    List<HistoryRecord> session(UUID sessionId);
    void insertFork(HistoryRecord record);
    void deleteFork(UUID id);
}
