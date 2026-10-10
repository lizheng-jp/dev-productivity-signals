/**
 * SPACE指標の定数定義。
 * バックエンドとの通信に使用するキーを定義します。
 */

export const SPACE_METRICS = {
  // --- Performance (パフォーマンス) ---
  /** マージされたMRの数 */
  mergedCount: 'mergedCount',
  /** マージまでの平均時間 */
  mergedLeadTimeHours: 'mergedLeadTimeHours',
  /** 発生したバグの数 */
  bugCausedCount: 'bugCausedCount',
  /** バグ修正にかかった平均時間 */
  bugFixLeadTimeHours: 'bugFixLeadTimeHours',

  // --- Activity (アクティビティ) ---
  /** コミット数 */
  commitCount: 'commitCount',
  /** 作成されたIssue of count */
  issueCreatedCount: 'issueCreatedCount',
  /** 発見されたバグの数 */
  bugFoundCount: 'bugFoundCount',
  /** 修正されたバグの数 */
  bugFixedCount: 'bugFixedCount',
  /** 追加されたコード行数 */
  linesAdded: 'linesAdded',
  /** 削除されたコード行数 */
  linesDeleted: 'linesDeleted',
  /** 合計コード行数 */
  linesTotal: 'linesTotal',


  // --- Communication & Collaboration (コミュニケーション & コラボレーション) ---
  /** 1つのMRあたりの平均レビュアー数 */
  reviewedCount: 'reviewedCount',
  /** コメント投稿頻度 */
  commentCount: 'commentCount',

  // --- Efficiency & Flow (効率 & フロー) ---
  /** MR作成から最初のレビューまでの平均待ち時間 */
  reviewWaitTime: 'reviewWaitTime',
  /** 1営業日あたりの平均連続集中時間 */
  uninterruptedFocusTimeHours: 'uninterruptedFocusTimeHours',
  /** 1営業日あたりの平均コンテキストスイッチ回数 */
  contextSwitchFrequency: 'contextSwitchFrequency',
  /** 1つのMRあたりの平均コメント数 */
  reviewCommentCount: 'reviewCommentCount',

  // --- Satisfaction (満足度) ---
  /** プロジェクト満足度調査スコア */
  satisfactionSurveyScore: 'satisfactionSurveyScore',
  /** プロジェクト満足度調査回答数 */
  satisfactionResponseCount: 'satisfactionResponseCount',
  /** 仕事満足・意義 */
  satisfactionJobMeaning: 'satisfactionJobMeaning',
  /** 開発者効力感 */
  satisfactionDeveloperEfficacy: 'satisfactionDeveloperEfficacy',
  /** 持続可能性 */
  satisfactionSustainability: 'satisfactionSustainability',
  /** 改善可能性 */
  satisfactionImprovementPotential: 'satisfactionImprovementPotential',
  /** 満足度スコアの算出元（survey: 満足度調査 / retention: コントリビューター定着率） */
  satisfactionSource: 'satisfactionSource',
  /** コントリビューター定着率（%）。満足度調査がない GitHub プロジェクトの満足度スコアに使う */
  contributorRetentionRate: 'contributorRetentionRate',
  /** 前期間・当期間の両方で活動したコントリビューター数 */
  retainedContributorCount: 'retainedContributorCount',
  /** 前期間に活動したコントリビューター数 */
  previousActiveContributorCount: 'previousActiveContributorCount',

  // --- Scores (スコア) ---
  /** パフォーマンススコア */
  performanceScore: 'performanceScore',
  /** アクティビティスコア */
  activityScore: 'activityScore',
  /** コミュニケーションスコア */
  communicationScore: 'communicationScore',
  /** 効率性スコア */
  efficiencyScore: 'efficiencyScore',
  /** 満足度スコア */
  satisfactionScore: 'satisfactionScore',
  /** SPACE総合スコア */
  spaceTotalScore: 'spaceTotalScore',

  // --- Others ---
  /** 活動の有無を示すフラグ */
  hasActivity: 'hasActivity',
} as const;
