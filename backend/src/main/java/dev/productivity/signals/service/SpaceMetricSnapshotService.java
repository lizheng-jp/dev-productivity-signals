package dev.productivity.signals.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SpaceMetricSnapshotService {

    private final Cache<String, Snapshot> snapshots;

    public SpaceMetricSnapshotService(
            @Value("${space-metrics.snapshot-cache.ttl-minutes:20}") long ttlMinutes,
            @Value("${space-metrics.snapshot-cache.maximum-size:500}") long maximumSize) {
        this.snapshots = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, ttlMinutes)))
                .maximumSize(Math.max(1, maximumSize))
                .build();
    }

    public String storeSingle(
            String projectId,
            String since,
            String until,
            String userName,
            String refName,
            Map<String, Object> metrics) {
        String snapshotId = UUID.randomUUID().toString();
        snapshots.put(snapshotId, new Snapshot(
                SnapshotType.SINGLE,
                context(projectId, since, until, userName, refName),
                copyMap(metrics),
                null));
        return snapshotId;
    }

    public String storeMembers(
            String projectId,
            String since,
            String until,
            String refName,
            List<Map<String, Object>> metrics) {
        String snapshotId = UUID.randomUUID().toString();
        snapshots.put(snapshotId, new Snapshot(
                SnapshotType.MEMBERS,
                context(projectId, since, until, null, refName),
                null,
                copyList(metrics)));
        return snapshotId;
    }

    public Optional<Map<String, Object>> findSingle(
            String snapshotId,
            String projectId,
            String since,
            String until,
            String userName,
            String refName) {
        Snapshot snapshot = getMatchingSnapshot(
                snapshotId,
                SnapshotType.SINGLE,
                context(projectId, since, until, userName, refName));
        return snapshot == null ? Optional.empty() : Optional.of(copyMap(snapshot.singleMetrics()));
    }

    public Optional<List<Map<String, Object>>> findMembers(
            String snapshotId,
            String projectId,
            String since,
            String until,
            String refName) {
        Snapshot snapshot = getMatchingSnapshot(
                snapshotId,
                SnapshotType.MEMBERS,
                context(projectId, since, until, null, refName));
        return snapshot == null ? Optional.empty() : Optional.of(copyList(snapshot.memberMetrics()));
    }

    public Optional<Map<String, Object>> findMember(
            String snapshotId,
            String projectId,
            String since,
            String until,
            String userName,
            String refName) {
        if (userName == null || userName.isBlank()) {
            return Optional.empty();
        }
        Snapshot snapshot = getMatchingSnapshot(
                snapshotId,
                SnapshotType.MEMBERS,
                context(projectId, since, until, null, refName));
        if (snapshot == null) {
            return Optional.empty();
        }
        return snapshot.memberMetrics().stream()
                .filter(metrics -> {
                    Object userCode = metrics.get("userCode");
                    return userCode != null && userName.equalsIgnoreCase(userCode.toString());
                })
                .findFirst()
                .map(this::copyMap);
    }

    private Snapshot getMatchingSnapshot(String snapshotId, SnapshotType type, SnapshotContext context) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return null;
        }
        Snapshot snapshot = snapshots.getIfPresent(snapshotId);
        if (snapshot == null || snapshot.type() != type || !snapshot.context().equals(context)) {
            return null;
        }
        return snapshot;
    }

    private SnapshotContext context(
            String projectId,
            String since,
            String until,
            String userName,
            String refName) {
        return new SnapshotContext(
                normalize(projectId, false),
                normalize(since, false),
                normalize(until, false),
                normalize(userName, true),
                normalize(refName, false));
    }

    private String normalize(String value, boolean ignoreCase) {
        String normalized = value == null ? "" : value.trim();
        return ignoreCase ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> source) {
        return source == null ? List.of() : source.stream().filter(Objects::nonNull).map(this::copyMap).toList();
    }

    private enum SnapshotType {
        SINGLE,
        MEMBERS
    }

    private record SnapshotContext(String projectId, String since, String until, String userName, String refName) {
    }

    private record Snapshot(
            SnapshotType type,
            SnapshotContext context,
            Map<String, Object> singleMetrics,
            List<Map<String, Object>> memberMetrics) {
    }
}
