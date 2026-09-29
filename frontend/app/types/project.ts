import type { MetricTrend } from '@/lib/api/metric-comparison';

export interface ProjectStat {
  projectId: string;
  trends?: Record<string, MetricTrend>;
  spaceMetrics?: import('@/lib/api/space-metrics').SpaceMetricsResponse;
  projectName: string;

  commitCount: number;
  totalMergeRequests: number;
  mergedCount: number;
  issueCreatedCount: number;
  bugCausedCount: number;
  bugFoundCount: number;
  bugsFixedCount: number;
  linesAdded?: number;
  linesDeleted?: number;
  linesTotal?: number;

  reviewWaitTime: number;
  uninterruptedFocusTimeHours?: number;
  contextSwitchFrequency?: number;
  reviewCommentCount: number;
  mergedLeadTimeHours: number;
  bugFixLeadTimeHours: number;
  reviewedCount: number;
  commentCount: number;
  satisfactionJobMeaning?: number;
  satisfactionDeveloperEfficacy?: number;
  satisfactionSustainability?: number;
  satisfactionImprovementPotential?: number;



  // Backward compatibility for ProjectAnalyticsView
  averageHoursToMerge: number;
  avgHoursToFirstReview: number;
  issuesCreatedCount: number;
  issuesCompletedCount: number;

  // SPACE Scores
  performanceScore: number;
  activityScore: number;
  communicationScore: number;
  efficiencyScore: number;
  satisfactionScore: number;
  totalScore: number;
  aiCorrectionReason?: string;

}
