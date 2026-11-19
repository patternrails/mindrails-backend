package com.mindrails.backend.history.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.model.HistoryPage;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.HistoryQueryStore;

import java.util.Set;
import java.util.UUID;

public final class DefaultHistoryService implements HistoryService {
    private final HistoryQueryStore store;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mindrails.backend.asset.registry.AssetRegistry assets;

    public DefaultHistoryService(HistoryQueryStore store) {
        this.store = store;
    }

    public ServiceResult<HistoryPage> list(HistoryFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size >= 1000) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Invalid query parameters");
        }
        if (filter.status() != null
                && !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(filter.status())) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Invalid query parameters");
        }
        return ServiceResult.success(store.list(filter, page, size));
    }

    public ServiceResult<HistoryRecord> get(UUID id) {
        return store.find(id)
                .map(record -> ServiceResult.success(record))
                .orElseGet(
                        () ->
                                ServiceResult.failure(
                                        ErrorCode.NOT_FOUND, "History record not found"));
    }

    public ServiceResult<String> metadata(UUID id) {
        return store.metadata(id).map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "History record not found"));
    }

    public ServiceResult<String> originalMessage(UUID id, int index) {
        if (index < 0) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Invalid message index");
        return store.originalMessage(id, index).map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "History message not found"));
    }

    public ServiceResult<Void> delete(UUID id) {
        boolean deleted = store.delete(id);
        if (deleted && assets != null) assets.clearRun(id);
        return deleted
                ? ServiceResult.success()
                : ServiceResult.failure(ErrorCode.NOT_FOUND, "History record not found");
    }

    public boolean recordingEnabled() {
        return true;
    }
}
