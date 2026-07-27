package com.itqianchen.agentdesign.domain.properties.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 耐久任务轮询与租约参数。 */
@ConfigurationProperties(prefix = "app.durable-tasks")
public record DurableTaskProperties(
        boolean dispatchEnabled,
        Duration pollInterval,
        Duration heartbeatInterval,
        Duration leaseDuration
) {

    public DurableTaskProperties {
        pollInterval = normalize(pollInterval, Duration.ofSeconds(2));
        heartbeatInterval = normalize(heartbeatInterval, Duration.ofSeconds(10));
        leaseDuration = normalize(leaseDuration, Duration.ofSeconds(60));
        if (leaseDuration.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("Durable task lease must cover at least two heartbeats");
        }
    }

    private static Duration normalize(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
