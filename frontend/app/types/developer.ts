import { ProjectMember } from '@/lib/api/members';
import type { MetricTrend } from '@/lib/api/metric-comparison';
import type { SpaceMetricsResponse } from '@/lib/api/space-metrics';

export interface AiMrEvaluation {
    mrIid: number;
    changeType?: string;
    complexity?: 'low' | 'mid' | 'high' | string;
    contribution?: 'low' | 'mid' | 'high' | string;
    reasoning?: string;
}

/**
 * 開発者のSPACE指標と統計情報を表すインターフェース。
 * camelCaseはフロントエンド内部で使用され、コメントは標準化された定数（SPACE_METRICS）との対応を示します。
 */
export interface DeveloperStat {
    member: ProjectMember;
    trends?: Record<string, MetricTrend>;

    // --- Performance (パフォーマンス) ---
    /** マージされたMRの数 (SPACE_METRICS.mergedCount) */
    mergedCount?: number;
    /** マージまでの平均時間 (SPACE_METRICS.mergedLeadTimeHours) */
    mergedLeadTimeHours?: number;
    /** 発生したバグの数 (SPACE_METRICS.bugCausedCount) */
    bugCausedCount?: number;
    /** バグ修正にかかった平均時間 (SPACE_METRICS.bugFixLeadTimeHours) */
    bugFixLeadTimeHours?: number;

    // --- Activity (アクティビティ) ---
    /** コミット数 (SPACE_METRICS.commitCount) */
    commitCount?: number;
    /** 作成されたIssueの数 (SPACE_METRICS.issueCreatedCount) */
    issueCreatedCount?: number;
    /** 発見されたバグの数 (SPACE_METRICS.bugFoundCount) */
    bugFoundCount?: number;
    /** 修正されたバグの数 (SPACE_METRICS.bugFixedCount) */
    bugFixedCount?: number;
    /** 追加された行数 (SPACE_METRICS.linesAdded) */
    linesAdded?: number;
    /** 削除された行数 (SPACE_METRICS.linesDeleted) */
    linesDeleted?: number;
    /** 変更された総行数 (SPACE_METRICS.linesTotal) */
    linesTotal?: number;

    // --- Communication & Collaboration (コミュニケーション & コラボレーション) ---
    /** 1つのMRあたりの平均レビュアー数 (SPACE_METRICS.reviewerCount) */
    reviewedCount?: number;
    /** コメント投稿頻度 (SPACE_METRICS.commentCount) */
    commentCount?: number;

    // --- Efficiency & Flow (効率 & フロー) ---
    /** 最初のレビューまでの平均待ち時間 (SPACE_METRICS.reviewWaitTime) */
    reviewWaitTime?: number;
    /** 1営業日あたりの平均連続集中時間 */
    uninterruptedFocusTimeHours?: number;
    /** 1営業日あたりの平均コンテキストスイッチ回数 */
    contextSwitchFrequency?: number;
    /** 1つのMRあたりの平均コメント数 (SPACE_METRICS.reviewCommentCount) */
    reviewCommentCount?: number;

    // --- Satisfaction (満足度) ---
    satisfactionJobMeaning?: number;
    satisfactionDeveloperEfficacy?: number;
    satisfactionSustainability?: number;
    satisfactionImprovementPotential?: number;

    // --- SPACE Scores (スコア) ---
    /** パフォーマンススコア (SPACE_METRICS.performanceScore) */
    performanceScore?: number;
    /** アクティビティスコア (SPACE_METRICS.activityScore) */
    activityScore?: number;
    /** コミュニケーションスコア (SPACE_METRICS.communicationScore) */
    communicationScore?: number;
    /** 効率性スコア (SPACE_METRICS.efficiencyScore) */
    efficiencyScore?: number;
    /** 満足度スコア (SPACE_METRICS.satisfactionScore) */
    satisfactionScore?: number;
    /** 総合スコア (SPACE_METRICS.spaceTotalScore) */
    totalScore?: number;
    /** 選択期間内に開発・レビュー・Issue等の活動があるか (SPACE_METRICS.hasActivity) */
    hasActivity?: boolean;
    aiEvaluations?: AiMrEvaluation[];
    spaceMetrics?: SpaceMetricsResponse;
}
