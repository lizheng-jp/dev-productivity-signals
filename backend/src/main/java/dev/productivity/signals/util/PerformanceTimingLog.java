package dev.productivity.signals.util;

import dev.productivity.signals.service.MonitoringMetricsService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
public final class PerformanceTimingLog {

    private static final ThreadLocal<PerformanceTimingLog> CURRENT = new ThreadLocal<>();
    private static volatile MonitoringMetricsService metricsService;

    private final String requestName;
    private final String projectId;
    private final String userName;
    private final String since;
    private final String until;
    private final String refName;
    private final long startedAtMs;
    private final Map<String, Long> durationsMs = new ConcurrentHashMap<>();
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    private PerformanceTimingLog(
            String requestName,
            String projectId,
            String userName,
            String since,
            String until,
            String refName) {
        this.requestName = requestName;
        this.projectId = projectId;
        this.userName = userName;
        this.since = since;
        this.until = until;
        this.refName = refName;
        this.startedAtMs = System.currentTimeMillis();
    }

    public static PerformanceTimingLog start(
            String requestName,
            String projectId,
            String userName,
            String since,
            String until,
            String refName) {
        PerformanceTimingLog timing = new PerformanceTimingLog(requestName, projectId, userName, since, until, refName);
        CURRENT.set(timing);
        return timing;
    }

    public static PerformanceTimingLog current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void setMetricsService(MonitoringMetricsService service) {
        metricsService = service;
    }

    public static <T> T withCurrent(PerformanceTimingLog timing, Supplier<T> supplier) {
        PerformanceTimingLog previous = CURRENT.get();
        CURRENT.set(timing);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static <T> T time(String stage, Supplier<T> supplier) {
        PerformanceTimingLog timing = CURRENT.get();
        if (timing == null) {
            return supplier.get();
        }

        long startedAt = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            timing.addDurationInternal(stage, System.currentTimeMillis() - startedAt);
        }
    }

    public static void time(String stage, Runnable runnable) {
        PerformanceTimingLog timing = CURRENT.get();
        if (timing == null) {
            runnable.run();
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            runnable.run();
        } finally {
            timing.addDurationInternal(stage, System.currentTimeMillis() - startedAt);
        }
    }

    public static void addDuration(String stage, long durationMs) {
        PerformanceTimingLog timing = CURRENT.get();
        if (timing != null) {
            timing.addDurationInternal(stage, durationMs);
        }
    }

    public static void incrementCount(String key) {
        addCount(key, 1);
    }

    public static void addCount(String key, long count) {
        PerformanceTimingLog timing = CURRENT.get();
        if (timing != null) {
            timing.counts.merge(key, count, Long::sum);
        }
    }

    private void addDurationInternal(String stage, long durationMs) {
        durationsMs.merge(stage, durationMs, Long::sum);
    }

    public void logSummary() {
        long totalMs = System.currentTimeMillis() - startedAtMs;

        Map<String, Long> orderedDurations = new LinkedHashMap<>();
        durationsMs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedDurations.put(entry.getKey(), entry.getValue()));

        Map<String, Long> orderedCounts = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedCounts.put(entry.getKey(), entry.getValue()));

        log.info(
                "PERF_SUMMARY request={} projectId={} target={} since={} until={} refName={} totalMs={} stages=[{}] counts=[{}]",
                requestName,
                projectId,
                userName == null || userName.isBlank() ? "project-wide" : userName,
                since,
                until,
                refName == null || refName.isBlank() ? "all" : refName,
                totalMs,
                formatMap(orderedDurations, "ms"),
                formatMap(orderedCounts, ""));

        MonitoringMetricsService service = metricsService;
        if (service != null) {
            service.recordRequestSummary(requestName, totalMs, orderedDurations, orderedCounts);
        }
    }

    private String formatMap(Map<String, Long> values, String suffix) {
        StringJoiner joiner = new StringJoiner(", ");
        values.forEach((key, value) -> joiner.add(key + "=" + value + suffix));
        return joiner.toString();
    }
}
