package dev.productivity.signals.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Caps public Agent questions, which each cost model calls: a sliding hourly limit per client and a fixed
 * daily limit for the whole service (UTC day). State is in memory, so a restart resets it. A limit of 0
 * disables that check.
 */
@Component
public class AgentRateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    public record Rejection(String reason, long retryAfterSeconds) {
    }

    private final int perClientPerHour;
    private final int perDay;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recentByClient = new HashMap<>();
    private LocalDate day;
    private int usedToday;

    @Autowired
    public AgentRateLimiter(@Value("${agent.rate-limit.per-client-per-hour:10}") int perClientPerHour,
            @Value("${agent.rate-limit.per-day:200}") int perDay) {
        this(perClientPerHour, perDay, Clock.systemUTC());
    }

    public AgentRateLimiter(int perClientPerHour, int perDay, Clock clock) {
        this.perClientPerHour = perClientPerHour;
        this.perDay = perDay;
        this.clock = clock;
    }

    /** Records the question and returns empty when allowed; a rejection is not recorded. */
    public synchronized Optional<Rejection> tryAcquire(String client) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
        if (perDay > 0 && usedToday >= perDay) {
            long untilTomorrow = Duration.between(now, today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                    .toSeconds();
            return Optional.of(new Rejection("Daily question limit reached", Math.max(1, untilTomorrow)));
        }
        Deque<Instant> recent = recentByClient.computeIfAbsent(client, ignored -> new ArrayDeque<>());
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(WINDOW))) {
            recent.pollFirst();
        }
        if (perClientPerHour > 0 && recent.size() >= perClientPerHour) {
            long retry = Duration.between(now, recent.peekFirst().plus(WINDOW)).toSeconds();
            return Optional.of(new Rejection("Hourly question limit reached", Math.max(1, retry)));
        }
        recent.addLast(now);
        usedToday++;
        if (recentByClient.size() > MAX_TRACKED_CLIENTS) {
            prune(now);
        }
        return Optional.empty();
    }

    private void prune(Instant now) {
        Iterator<Deque<Instant>> entries = recentByClient.values().iterator();
        while (entries.hasNext()) {
            Deque<Instant> recent = entries.next();
            if (recent.isEmpty() || !recent.peekLast().isAfter(now.minus(WINDOW))) {
                entries.remove();
            }
        }
    }
}
