package com.mindrails.backend.websearch;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

record WebSearchQuery(String text, String freshness, int count, boolean summary,
        List<String> include, List<String> exclude) {
    private static final Set<String> RELATIVE = Set.of("noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");
    private static final Pattern DOMAIN =
            Pattern.compile("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    static WebSearchQuery create(WebSearchProvider provider, String query, String freshness, Integer count,
            Boolean summary, String include, String exclude) {
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new IllegalArgumentException("query must contain 1..2000 characters");
        }
        int limit = count == null ? 10 : count;
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("count must be between 1 and 50");
        String range = freshness == null || freshness.isBlank()
                ? (String) provider.options().getOrDefault("freshness", "noLimit") : freshness;
        if (!validFreshness(range)) throw new IllegalArgumentException("Invalid freshness date or range");
        boolean includeSummary = summary == null ? (boolean) provider.options().getOrDefault("summary", true) : summary;
        return new WebSearchQuery(query.strip(), range, limit, includeSummary,
                domains("include", include), domains("exclude", exclude));
    }

    static boolean validFreshness(String value) {
        if (RELATIVE.contains(value)) return true;
        try {
            dateRange(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    DateRange dates() {
        if ("noLimit".equals(freshness)) return null;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (freshness) {
            case "oneDay" -> new DateRange(today.minusDays(1), today);
            case "oneWeek" -> new DateRange(today.minusWeeks(1), today);
            case "oneMonth" -> new DateRange(today.minusMonths(1), today);
            case "oneYear" -> new DateRange(today.minusYears(1), today);
            default -> dateRange(freshness);
        };
    }

    private static DateRange dateRange(String value) {
        String[] parts = value.split("\\.\\.", -1);
        if (parts.length > 2) throw new IllegalArgumentException();
        LocalDate start = LocalDate.parse(parts[0]);
        LocalDate end = parts.length == 1 ? start : LocalDate.parse(parts[1]);
        if (end.isBefore(start)) throw new IllegalArgumentException();
        return new DateRange(start, end);
    }

    private static List<String> domains(String name, String value) {
        if (value == null || value.isBlank()) return List.of();
        var domains = Arrays.stream(value.split("[|,]", -1)).map(String::strip).toList();
        if (domains.size() > 100 || domains.stream().anyMatch(domain -> !DOMAIN.matcher(domain).matches())) {
            throw new IllegalArgumentException(name + " must contain at most 100 domains");
        }
        return domains;
    }

    record DateRange(LocalDate start, LocalDate end) {}
}
