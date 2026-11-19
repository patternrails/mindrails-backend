package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class UnixCronTest {
    @Test void fiveFieldScheduleUsesExplicitTimezone() {
        assertEquals(Instant.parse("2026-10-26T01:00:00Z"),new UnixCron("0 9 * * MON-FRI").next(Instant.parse("2026-10-23T02:00:00Z"),ZoneId.of("Asia/Shanghai")));
    }
    @Test void dayOfMonthAndWeekdayUseUnixOrSemantics() {
        var cron=new UnixCron("0 9 1 * MON");
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"),cron.next(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"),cron.next(Instant.parse("2026-10-31T00:00:00Z"),ZoneOffset.UTC));
    }
    @Test void rejectsSixFieldsMacrosAndSpringExtensions() {
        for(String expression:new String[]{"0 0 9 * * *","@daily","0 9 L * *","0 9 * * MON#1","0 9 ? * *","60 9 * * *","0 9 * * FUNDAY"})
            assertThrows(IllegalArgumentException.class,() -> new UnixCron(expression),expression);
    }
}
