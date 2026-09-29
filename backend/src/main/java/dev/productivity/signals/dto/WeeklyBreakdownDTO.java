package dev.productivity.signals.dto;

public class WeeklyBreakdownDTO {

    private String weekStart;
    private String weekEnd;
    private long count;

    public WeeklyBreakdownDTO(String weekStart, String weekEnd, long count) {
        this.weekStart = weekStart;
        this.weekEnd = weekEnd;
        this.count = count;
    }

    public String getWeekStart() {
        return weekStart;
    }

    public void setWeekStart(String weekStart) {
        this.weekStart = weekStart;
    }

    public String getWeekEnd() {
        return weekEnd;
    }

    public void setWeekEnd(String weekEnd) {
        this.weekEnd = weekEnd;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
