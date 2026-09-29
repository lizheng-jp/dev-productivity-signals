package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IssueStatsDTO {

    // イシュー作成件数
    private long createdCount;
    // イシュー解決件数
    private long completedCount;
    // バグ発見件数
    private long bugFoundCount;
    // バグ発生件数
    private long bugCausedCount;
    // バグ修正件数
    private long bugFixedCount;
    // 平均バグ修正時間（時間）
    private double avgBugFixedHours;



}
