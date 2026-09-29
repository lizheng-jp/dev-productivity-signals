package dev.productivity.signals.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import dev.productivity.signals.util.PerformanceTimingLog;

import java.time.Duration;
import java.util.Map;

@Service
public class MonitoringMetricsService {

    private final MeterRegistry meterRegistry;

    public MonitoringMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bindToPerformanceTimingLog() {
        PerformanceTimingLog.setMetricsService(this);
    }

    public void recordRequestSummary(String requestName, long totalMs, Map<String, Long> durationsMs,
            Map<String, Long> counts) {
        Timer.builder("app_request_duration")
                .description("Application request duration")
                .tags("request", safeTag(requestName))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofMillis(totalMs));

        Counter.builder("app_request_count")
                .description("Application request count")
                .tags("request", safeTag(requestName))
                .register(meterRegistry)
                .increment();

        durationsMs.forEach((stage, durationMs) -> recordStageDuration(requestName, stage, durationMs));
        counts.forEach((name, count) -> recordCount(requestName, name, count));
    }

    public void recordGeminiUsage(String model, String operation, int promptTokens, int candidatesTokens,
            int thoughtsTokens, int totalTokens) {
        Tags tags = Tags.of("model", safeTag(model), "operation", safeTag(operation));
        incrementCounter("gemini_token_count", "type", "prompt", tags, promptTokens);
        incrementCounter("gemini_token_count", "type", "candidates", tags, candidatesTokens);
        incrementCounter("gemini_token_count", "type", "thoughts", tags, thoughtsTokens);
        incrementCounter("gemini_token_count", "type", "total", tags, totalTokens);
    }

    private void recordStageDuration(String requestName, String stage, long durationMs) {
        Timer.builder("app_stage_duration")
                .description("Application internal stage duration")
                .tags("request", safeTag(requestName), "stage", safeTag(stage))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    private void recordCount(String requestName, String name, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("app_operation_count")
                .description("Application operation count")
                .tags("request", safeTag(requestName), "operation", safeTag(name))
                .register(meterRegistry)
                .increment(count);
    }

    private void incrementCounter(String metricName, String tagKey, String tagValue, Tags baseTags, int amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(metricName)
                .description("Gemini token usage count")
                .tags(baseTags.and(tagKey, tagValue))
                .register(meterRegistry)
                .increment(amount);
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
