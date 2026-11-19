package com.mindrails.backend.metrics;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("mindrails.metrics")
public class MetricsProperties {
    private int maxRuns = 1000;
    private long maxBufferBytes = 16 * 1024 * 1024;
    private int maxDetailsPerRun = 500;
    private int batchSize = 50;
    private int poolSize = 2;
    private Duration flushInterval = Duration.ofSeconds(1);
    private Duration lateCallbackRetention = Duration.ofSeconds(60);
    private Duration retryMaxAge = Duration.ofMinutes(15);
    private Duration detailsRetention = Duration.ofDays(90);
    private Duration runsRetention = Duration.ofDays(365);
    /** Only an explicitly confirmed stopped process may be recovered; never sweep other live instances. */
    private String recoverInstanceId;

    public boolean valid() {
        return maxRuns > 0 && maxBufferBytes >= 8192 && maxDetailsPerRun > 0 && maxDetailsPerRun <= 10000
                && batchSize > 0 && poolSize > 0 && positive(flushInterval)
                && positive(lateCallbackRetention) && positive(retryMaxAge) && positive(detailsRetention)
                && positive(runsRetention) && runsRetention.compareTo(detailsRetention) >= 0;
    }
    private static boolean positive(Duration value) { return value != null && !value.isZero() && !value.isNegative(); }
}
