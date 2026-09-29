package dev.productivity.signals.dto;

import java.util.List;

public class CommitCountResponseDTO {

    private long count;
    private List<WeeklyBreakdownDTO> weeklyBreakdown;

    public CommitCountResponseDTO(long count, List<WeeklyBreakdownDTO> weeklyBreakdown) {
        this.count = count;
        this.weeklyBreakdown = weeklyBreakdown;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<WeeklyBreakdownDTO> getWeeklyBreakdown() {
        return weeklyBreakdown;
    }

    public void setWeeklyBreakdown(List<WeeklyBreakdownDTO> weeklyBreakdown) {
        this.weeklyBreakdown = weeklyBreakdown;
    }
}
