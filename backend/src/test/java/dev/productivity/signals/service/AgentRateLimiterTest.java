package dev.productivity.signals.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRateLimiterTest {
    private static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void limitsEachClientWithinASlidingHour() {
        var clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
        var limiter = new AgentRateLimiter(2, 0, clock);
        assertThat(limiter.tryAcquire("a")).isEmpty();
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        assertThat(limiter.tryAcquire("a")).isEmpty();
        var rejected = limiter.tryAcquire("a");
        assertThat(rejected).isPresent();
        assertThat(rejected.get().reason()).isEqualTo("Hourly question limit reached");
        assertThat(rejected.get().retryAfterSeconds()).isEqualTo(Duration.ofMinutes(30).toSeconds());
        assertThat(limiter.tryAcquire("b")).isEmpty();
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(limiter.tryAcquire("a")).isEmpty();
    }

    @Test
    void capsTheWholeServicePerUtcDayAndResets() {
        var clock = new MutableClock(Instant.parse("2026-10-11T23:00:00Z"));
        var limiter = new AgentRateLimiter(0, 2, clock);
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("b")).isEmpty();
        var rejected = limiter.tryAcquire("c");
        assertThat(rejected).isPresent();
        assertThat(rejected.get().reason()).isEqualTo("Daily question limit reached");
        assertThat(rejected.get().retryAfterSeconds()).isEqualTo(Duration.ofHours(1).toSeconds());
        clock.now = Instant.parse("2026-10-12T00:00:01Z");
        assertThat(limiter.tryAcquire("c")).isEmpty();
    }

    @Test
    void rejectedQuestionsDoNotUseQuotaAndZeroDisables() {
        var clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
        var limiter = new AgentRateLimiter(1, 2, clock);
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("a")).isPresent();
        assertThat(limiter.tryAcquire("a")).isPresent();
        assertThat(limiter.tryAcquire("b")).isEmpty();
        var unlimited = new AgentRateLimiter(0, 0, clock);
        for (int i = 0; i < 100; i++) {
            assertThat(unlimited.tryAcquire("a")).isEmpty();
        }
    }
}
