package com.mindrails.backend.history.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.model.HistoryPage;

import java.util.UUID;

public interface HistoryService {
    ServiceResult<HistoryPage> list(HistoryFilter filter, int page, int size);

    ServiceResult<HistoryRecord> get(UUID id);

    ServiceResult<String> metadata(UUID id);

    ServiceResult<String> originalMessage(UUID id, int index);

    ServiceResult<Void> delete(UUID id);

    boolean recordingEnabled();
}
