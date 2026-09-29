package dev.productivity.signals.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MetricComparison {
    private MetricComparison() {
    }

    public record Window(LocalDate since, LocalDate until) {
        public long days() {
            return ChronoUnit.DAYS.between(since, until) + 1;
        }

        public ZonedDateTime start() {
            return since.atStartOfDay(ZoneOffset.UTC);
        }

        public ZonedDateTime end() {
            return until.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1);
        }
    }

    public record Periods(Window current, Window previous) {
    }

    public record Trend(
            Double current,
            Double previous,
            Double change,
            Double percentChange,
            String status,
            boolean lowerIsBetter,
            boolean dailyNormalized) {
    }

    private static final Set<String> COUNTS = Set.of(
            "commitCount", "mergedCount", "issueCreatedCount", "bugFoundCount",
            "bugCausedCount", "bugFixedCount", "linesAdded", "linesDeleted",
            "linesTotal", "reviewedCount", "commentCount");
    private static final Set<String> LOWER_IS_BETTER = Set.of(
            "mergedLeadTimeHours", "bugCausedCount", "bugFixLeadTimeHours",
            "reviewWaitTime", "contextSwitchFrequency");

    public static Periods periods(String since, String until, int previousDays) {
        try {
            LocalDate start = LocalDate.parse(since);
            LocalDate end = LocalDate.parse(until);
            if (end.isBefore(start)
                    || previousDays < 1
                    || previousDays > 366
                    || ChronoUnit.DAYS.between(start, end) > 365) {
                throw new IllegalArgumentException();
            }
            return new Periods(
                    new Window(start, end),
                    new Window(start.minusDays(previousDays), start.minusDays(1)));
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use a valid date range up to 366 days and previousDays between 1 and 366");
        }
    }

    public static Map<String, Trend> compare(
            Map<String, Object> current,
            Map<String, Object> previous,
            Periods periods) {
        Map<String, Trend> result = new LinkedHashMap<>();
        for (String key : current.keySet()) {
            if (key.equals("satisfactionResponseCount") || !(current.get(key) instanceof Number)) {
                continue;
            }

            Double currentValue = number(current, key);
            Double previousValue = number(previous, key);
            boolean normalized = COUNTS.contains(key)
                    && periods.current().days() != periods.previous().days();
            double currentRate = currentValue == null
                    ? 0
                    : normalized ? currentValue / periods.current().days() : currentValue;
            double previousRate = previousValue == null
                    ? 0
                    : normalized ? previousValue / periods.previous().days() : previousValue;
            String status = currentValue == null || previousValue == null
                    ? "unavailable"
                    : previousRate == 0 && currentRate != 0 ? "new" : "comparable";
            Double change = currentValue == null || previousValue == null
                    ? null
                    : currentValue - previousValue;
            Double percentChange = currentValue == null || previousValue == null
                    || (previousRate == 0 && currentRate != 0)
                    ? null
                    : previousRate == 0 ? 0 : (currentRate - previousRate) / Math.abs(previousRate) * 100;
            result.put(key, new Trend(
                    currentValue,
                    previousValue,
                    change,
                    percentChange,
                    status,
                    LOWER_IS_BETTER.contains(key),
                    normalized));
        }
        return result;
    }

    private static Double number(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return null;
        }
        if (key.equals("mergedLeadTimeHours")
                && !(metrics.get("mergedCount") instanceof Number count && count.doubleValue() > 0)) {
            return null;
        }
        if (key.equals("bugFixLeadTimeHours")
                && !(metrics.get("bugFixedCount") instanceof Number count && count.doubleValue() > 0)) {
            return null;
        }
        if (key.equals("reviewWaitTime") && number.doubleValue() <= 0) {
            return null;
        }
        if (key.startsWith("satisfaction")
                && !(metrics.get("satisfactionResponseCount") instanceof Number count
                && count.doubleValue() > 0)) {
            return null;
        }
        return number.doubleValue();
    }
}
