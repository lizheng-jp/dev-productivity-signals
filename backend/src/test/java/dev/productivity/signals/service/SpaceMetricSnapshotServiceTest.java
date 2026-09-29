package dev.productivity.signals.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceMetricSnapshotServiceTest {

    private final SpaceMetricSnapshotService service = new SpaceMetricSnapshotService(20, 500);

    @Test
    void singleSnapshotRequiresMatchingContextAndReturnsCopies() {
        Map<String, Object> original = new HashMap<>();
        original.put("spaceTotalScore", 72.5);

        String snapshotId = service.storeSingle(
                "10", "2026-07-01", "2026-07-10", "UserA", "main", original);
        original.put("spaceTotalScore", 1.0);

        Map<String, Object> firstRead = service.findSingle(
                snapshotId, "10", "2026-07-01", "2026-07-10", "usera", "main").orElseThrow();
        assertThat(firstRead.get("spaceTotalScore")).isEqualTo(72.5);

        firstRead.put("spaceTotalScore", 2.0);
        Map<String, Object> secondRead = service.findSingle(
                snapshotId, "10", "2026-07-01", "2026-07-10", "USERA", "main").orElseThrow();
        assertThat(secondRead.get("spaceTotalScore")).isEqualTo(72.5);

        assertThat(service.findSingle(
                snapshotId, "11", "2026-07-01", "2026-07-10", "usera", "main")).isEmpty();
    }

    @Test
    void memberSnapshotCannotBeReadAsSingleSnapshot() {
        String snapshotId = service.storeMembers(
                "10",
                "2026-07-01",
                "2026-07-10",
                "main",
                List.of(Map.of("userCode", "USERA", "spaceTotalScore", 80.0)));

        assertThat(service.findMembers(
                snapshotId, "10", "2026-07-01", "2026-07-10", "main")).isPresent();
        assertThat(service.findSingle(
                snapshotId, "10", "2026-07-01", "2026-07-10", null, "main")).isEmpty();

        Map<String, Object> member = service.findMember(
                snapshotId, "10", "2026-07-01", "2026-07-10", "usera", "main").orElseThrow();
        assertThat(member.get("spaceTotalScore")).isEqualTo(80.0);
        assertThat(service.findMember(
                snapshotId, "10", "2026-07-01", "2026-07-10", "USERB", "main")).isEmpty();
        assertThat(service.findMember(
                snapshotId, "10", "2026-07-01", "2026-07-10", "USERA", "develop")).isEmpty();
    }
}
