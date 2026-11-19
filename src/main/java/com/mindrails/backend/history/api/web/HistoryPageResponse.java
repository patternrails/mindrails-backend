package com.mindrails.backend.history.api.web;

import com.mindrails.backend.history.model.HistorySummary;

import java.util.List;

public record HistoryPageResponse(
        List<HistorySummary> items,
        long total,
        long visibleTotal,
        int limit,
        int page,
        int size,
        boolean hasNext,
        boolean recordingEnabled) {}
