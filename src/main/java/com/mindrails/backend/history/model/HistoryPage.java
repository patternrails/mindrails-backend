package com.mindrails.backend.history.model;

import java.util.List;

public record HistoryPage(
        List<HistorySummary> items,
        long total,
        long visibleTotal,
        int limit,
        int page,
        int size,
        boolean hasNext) {}
