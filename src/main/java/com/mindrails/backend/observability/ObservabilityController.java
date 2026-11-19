package com.mindrails.backend.observability;

import com.mindrails.backend.metrics.MetricsProperties;
import com.mindrails.backend.metrics.MetricsHealth;
import com.mindrails.backend.metrics.MetricsSnapshotBuffer;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ObservabilityController {
    private final MetricsQueryStore metrics;
    private final MetricsProperties metricsProperties;
    private final MetricsHealth metricsHealth;
    private final MetricsSnapshotBuffer metricsBuffer;

    public ObservabilityController(
            MetricsQueryStore metrics,
            MetricsProperties metricsProperties,
            MetricsHealth metricsHealth,
            MetricsSnapshotBuffer metricsBuffer) {
        this.metrics = metrics;
        this.metricsProperties = metricsProperties;
        this.metricsHealth = metricsHealth;
        this.metricsBuffer = metricsBuffer;
    }

    public record Overview(
            Instant from,
            Instant to,
            String timeZone,
            boolean collectionEnabled,
            Instant lastPersistedAt,
            MetricsQueryStore.Totals summary,
            List<MetricsQueryStore.Point> trend,
            List<MetricsQueryStore.Model> models,
            Map<String, Object> capabilities,
            Map<String, Long> health) {}

    public record Error(String code, String message) {}

    public record SessionMetrics(
            UUID sessionId,
            boolean collectionEnabled,
            Instant lastPersistedAt,
            MetricsQueryStore.Totals summary) {}

    @GetMapping("/metrics/sessions/{sessionId}")
    public SessionMetrics sessionMetrics(@PathVariable UUID sessionId) {
        var data = metrics.session(sessionId);
        return new SessionMetrics(
                sessionId,
                metricsProperties.valid(),
                data.lastPersistedAt(),
                data.summary());
    }

    @GetMapping("/metrics/overview")
    public Overview metrics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID sessionId) {
        Instant end = to == null ? Instant.now() : Instant.parse(to),
                start = from == null ? end.minus(Duration.ofDays(7)) : Instant.parse(from);
        if (!start.isBefore(end)
                || Duration.between(start, end).compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("Range must be positive and at most 90 days");
        }
        var data = metrics.overview(new ArchiveFilter(start, end, agentId, sessionId, null));
        var existing = new HashMap<LocalDate, MetricsQueryStore.Totals>();
        data.trend().forEach(p -> existing.put(p.day(), p.totals()));
        var points = new ArrayList<MetricsQueryStore.Point>();
        for (var day = start.atOffset(ZoneOffset.UTC).toLocalDate();
                !day.atStartOfDay().toInstant(ZoneOffset.UTC).isAfter(end.minusNanos(1));
                day = day.plusDays(1)) {
            points.add(
                    new MetricsQueryStore.Point(
                            day, existing.getOrDefault(day, MetricsAggregates.empty())));
        }
        var capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("functionCalls", data.summary().functionCalls());
        capabilities.put("mcpToolCalls", data.summary().mcpToolCalls());
        capabilities.put("skillLoads", data.summary().skillLoads());
        capabilities.put("skillExecutions", null);
        var h = metricsHealth;
        return new Overview(
                start,
                end,
                "UTC",
                metricsProperties.valid(),
                data.lastPersistedAt(),
                data.summary(),
                points,
                data.models(),
                capabilities,
                Map.of(
                        "pendingRuns",
                        (long) metricsBuffer.size(),
                        "droppedRuns",
                        h.droppedRuns.get(),
                        "droppedCheckpoints",
                        h.droppedCheckpoints.get(),
                        "writeFailures",
                        h.writeFailures.get(),
                        "collectionFailures",
                        h.collectionFailures.get(),
                        "orphanCallbacks",
                        h.orphanCallbacks.get(),
                        "truncatedDetails",
                        h.truncatedDetails.get()));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        DateTimeException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Error> invalid(RuntimeException error) {
        return ResponseEntity.badRequest()
                .body(new Error("INVALID_ARGUMENT", "Invalid query parameters"));
    }

    @ExceptionHandler({
        DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    public ResponseEntity<Error> unavailable(RuntimeException error) {
        return ResponseEntity.status(503)
                .body(new Error("UNAVAILABLE", "Archive storage is unavailable"));
    }
}
