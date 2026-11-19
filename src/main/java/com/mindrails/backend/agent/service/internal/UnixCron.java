package com.mindrails.backend.agent.service.internal;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** Five-field Unix cron, including OR semantics when both day fields are restricted. */
public final class UnixCron {
    private final List<CronExpression> expressions;
    public UnixCron(String value) {
        if (value == null) throw new IllegalArgumentException("Cron expression is required");
        String[] f = value.strip().split("\\s+");
        if (f.length != 5) throw new IllegalArgumentException("cronjob must have five fields: minute hour day month weekday");
        for (int i=0; i<f.length; i++) {
            String allowed = i == 3 || i == 4 ? "[0-9A-Za-z*,/\\-]+" : "[0-9*,/\\-]+";
            if (!f[i].matches(allowed) || f[i].matches(".*[?#?].*")) throw new IllegalArgumentException("Unsupported Unix cron field");
        }
        // Restrict names to Unix month/weekday names; reject Spring's L/W/# extensions.
        if (!f[3].toUpperCase(Locale.ROOT).replaceAll("JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC", "").matches("[0-9*,/\\-]*")
                || !f[4].toUpperCase(Locale.ROOT).replaceAll("SUN|MON|TUE|WED|THU|FRI|SAT", "").matches("[0-9*,/\\-]*"))
            throw new IllegalArgumentException("Unsupported Unix cron name");
        if (!f[2].startsWith("*") && !f[4].startsWith("*")) {
            expressions = List.of(CronExpression.parse("0 " + f[0]+" "+f[1]+" "+f[2]+" "+f[3]+" *"),
                    CronExpression.parse("0 " + f[0]+" "+f[1]+" * "+f[3]+" "+f[4]));
        } else expressions = List.of(CronExpression.parse("0 " + String.join(" ", f)));
    }
    public Instant next(Instant after, ZoneId zone) {
        return expressions.stream().map(e -> e.next(after.atZone(zone))).filter(Objects::nonNull)
                .map(ZonedDateTime::toInstant).min(Comparator.naturalOrder()).orElse(null);
    }
}
