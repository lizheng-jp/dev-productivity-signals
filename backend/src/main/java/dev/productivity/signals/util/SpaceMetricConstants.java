package dev.productivity.signals.util;

/**
 * SPACE指標の定数クラス。
 * 前后端交互のデータ、および計算ロジックで使用するキーを定義します。
 */
public final class SpaceMetricConstants {

    private SpaceMetricConstants() {
        // インスタンス化を防止
    }

    // --- Performance (パフォーマンス) ---
    /**
     * マージされたMRの数。
     */
    public static final String mergedCount = "mergedCount";

    public static final String mergedLeadTimeHours = "mergedLeadTimeHours";

    /**
     * 発生したバグの数。
     */
    public static final String bugCausedCount = "bugCausedCount";

    public static final String bugFixLeadTimeHours = "bugFixLeadTimeHours";

    // --- Activity (アクティビティ) ---
    /**
     * コミット数。
     */
    public static final String commitCount = "commitCount";

    /**
     * 作成されたIssueの数。
     */
    public static final String issueCreatedCount = "issueCreatedCount";

    /**
     * 発見されたバグの数。
     */
    public static final String bugFoundCount = "bugFoundCount";

    /**
     * 修正されたバグの数。
     */
    public static final String bugFixedCount = "bugFixedCount";

    /**
     * 追加された行数。
     */
    public static final String linesAdded = "linesAdded";

    /**
     * 削除された行数。
     */
    public static final String linesDeleted = "linesDeleted";

    /**
     * 総行数。
     */
    public static final String linesTotal = "linesTotal";


    // --- Communication & Collaboration (コミュニケーション & コラボレーション) ---
    /**
     * レビュアした数。
     */
    public static final String reviewedCount = "reviewedCount";

    /**
     * コメント投稿頻度。
     */
    public static final String commentCount = "commentCount";

    // --- Efficiency & Flow (効率 & フロー) ---
    /**
     * レビュー待ち時間。
     */
    public static final String reviewWaitTime = "reviewWaitTime";

    /**
     * 1営業日あたりの平均連続集中時間。
     */
    public static final String uninterruptedFocusTimeHours = "uninterruptedFocusTimeHours";

    /**
     * 1営業日あたりの平均コンテキストスイッチ回数。
     */
    public static final String contextSwitchFrequency = "contextSwitchFrequency";

    /**
     * PRコメント数。
     */
    public static final String reviewCommentCount = "reviewCommentCount";

    // --- Satisfaction (満足度) ---
    /**
     * プロジェクト満足度調査から算出したスコア。
     */
    public static final String satisfactionSurveyScore = "satisfactionSurveyScore";

    /**
     * プロジェクト満足度調査の回答数。
     */
    public static final String satisfactionResponseCount = "satisfactionResponseCount";

    public static final String satisfactionJobMeaning = "satisfactionJobMeaning";

    public static final String satisfactionDeveloperEfficacy = "satisfactionDeveloperEfficacy";

    public static final String satisfactionSustainability = "satisfactionSustainability";

    public static final String satisfactionImprovementPotential = "satisfactionImprovementPotential";

    // --- Scores (スコア) ---
    /** パフォーマンス次元のスコア。 */
    public static final String performanceScore = "performanceScore";
    /** アクティビティ次元のスコア。 */
    public static final String activityScore = "activityScore";
    /** コミュニケーション次元のスコア。 */
    public static final String communicationScore = "communicationScore";
    /** 効率性次元のスコア。 */
    public static final String efficiencyScore = "efficiencyScore";
    /** 満足度次元のスコア。 */
    public static final String satisfactionScore = "satisfactionScore";
    /** SPACE総合スコア。 */
    public static final String spaceTotalScore = "spaceTotalScore";

    // --- Flags & Others ---
    /** 活動の有無を示すフラグ。 */
    public static final String hasActivity = "hasActivity";
}
