package dev.productivity.signals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestStatsDTO {
    // マージリクエストを作成した総数
    private int createdCount;
    // 作成し、かつマージされた総数
    private int createdAndMergedCount;
    // 期間内にマージされた、自身が作成したMR数
    private int mergedCount;
    // マージリクエストに紐づく総コメント数
    private int totalCommentCount;
    // 期間内のマージリクエストの総レビュアー数
    private int totalReviewerCount;
    // 作成したマージリクエストの平均リードタイム（時間）
    private double avgHoursToMergeByCreated;
    // 期間内にマージされたMRの平均リードタイム（時間）
    private double avgHoursToMergeByMerged;
    // マージリクエストが行われてから最初にコメントされるまでの平均リードタイム（時間）
    private double avgHoursToFirstReview;
    // 自身が他人のMRに行なったレビュー数
    private int givenReviewCount;
    // 自身が他人のMRに行なったコメント数
    private int givenCommentCount;
}
