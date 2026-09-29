package dev.productivity.signals.dto;

import java.util.List;

/**
 * Represents the comprehensive commit metrics response, including total count,
 * weekly breakdown, and aggregated line statistics.
 */
public class CommitMetricsResponseDTO {
    private int totalCommitCount;
    private List<WeeklyBreakdownDTO> weeklyBreakdown;
    private CommitStatsDTO commitStats; // New field for aggregated line stats

    public CommitMetricsResponseDTO(int totalCommitCount, List<WeeklyBreakdownDTO> weeklyBreakdown,
            CommitStatsDTO commitStats) {
        this.totalCommitCount = totalCommitCount;
        this.weeklyBreakdown = weeklyBreakdown;
        this.commitStats = commitStats;
    }

    // Getters
    public int getTotalCommitCount() {
        return totalCommitCount;
    }

    public List<WeeklyBreakdownDTO> getWeeklyBreakdown() {
        return weeklyBreakdown;
    }

    public CommitStatsDTO getCommitStats() {
        return commitStats;
    }
}