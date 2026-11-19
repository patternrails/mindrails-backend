package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryRecord;

/** Write-only archive. Entries merge by server turn ID; the first terminal record is immutable. */
public interface HistoryStore {
    void insert(HistoryRecord record);
}
