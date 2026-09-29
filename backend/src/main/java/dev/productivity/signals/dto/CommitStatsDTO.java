package dev.productivity.signals.dto;

/**
 * Represents the aggregated line statistics (additions, deletions, total) for a
 * set of commits.
 */
public class CommitStatsDTO {
    private int linesAdded;
    private int linesDeleted;
    private int linesTotal;

    public CommitStatsDTO(int linesAdded, int linesDeleted, int linesTotal) {
        this.linesAdded = linesAdded;
        this.linesDeleted = linesDeleted;
        this.linesTotal = linesTotal;
    }

    // Getters
    public int getLinesAdded() {
        return linesAdded;
    }

    public int getLinesDeleted() {
        return linesDeleted;
    }

    public int getLinesTotal() {
        return linesTotal;
    }
    // Note: Setters are omitted as DTOs are often immutable after creation.
}