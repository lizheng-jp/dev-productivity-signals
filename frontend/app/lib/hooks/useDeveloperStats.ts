import { useMemo } from 'react';
import { useMetricComparison } from './useMetricComparison';
import { DeveloperStat } from '@/types/developer';
import { getProjectMembers } from '@/lib/api/members';
import { type SpaceMetricsResponse } from '@/lib/api/space-metrics';
import { SPACE_METRICS } from '@/lib/api/space-metrics.constants';
import { DateRange } from 'react-day-picker';

const ACTIVITY_METRICS = [
  SPACE_METRICS.mergedCount,
  SPACE_METRICS.bugCausedCount,
  SPACE_METRICS.bugFixLeadTimeHours,
  SPACE_METRICS.commitCount,
  SPACE_METRICS.bugFoundCount,
  SPACE_METRICS.bugFixedCount,
  SPACE_METRICS.issueCreatedCount,
  SPACE_METRICS.reviewedCount,
  SPACE_METRICS.commentCount,
  SPACE_METRICS.reviewWaitTime,
  SPACE_METRICS.uninterruptedFocusTimeHours,
  SPACE_METRICS.contextSwitchFrequency,
  SPACE_METRICS.reviewCommentCount,
  SPACE_METRICS.linesAdded,
  SPACE_METRICS.linesDeleted,
  SPACE_METRICS.linesTotal,
] as const;

const hasDevelopmentActivity = (metrics: SpaceMetricsResponse) => {
  const hasActivity = metrics[SPACE_METRICS.hasActivity];
  if (typeof hasActivity === 'boolean') {
    return hasActivity;
  }
  return ACTIVITY_METRICS.some(key => Number(metrics[key] || 0) > 0);
};

const SCORE_KEYS = [
  SPACE_METRICS.performanceScore,
  SPACE_METRICS.activityScore,
  SPACE_METRICS.communicationScore,
  SPACE_METRICS.efficiencyScore,
  SPACE_METRICS.satisfactionScore,
  SPACE_METRICS.spaceTotalScore,
] as const;

const withAiCorrectedScores = (metrics: SpaceMetricsResponse) => {
  const aiCorrected = metrics.aiCorrected;
  if (!aiCorrected || typeof aiCorrected !== 'object') {
    return metrics;
  }

  const correctedMetrics: SpaceMetricsResponse = { ...metrics };
  SCORE_KEYS.forEach((key) => {
    const value = (aiCorrected as SpaceMetricsResponse)[key];
    if (typeof value === 'number') {
      correctedMetrics[key] = value;
    }
  });
  return correctedMetrics;
};

export const buildDeveloperStats = (
  members: Awaited<ReturnType<typeof getProjectMembers>>,
  spaceMetrics: SpaceMetricsResponse[],
  sourceSpaceMetrics: SpaceMetricsResponse[] = spaceMetrics
) => {
  const spaceMetricsMap = new Map(spaceMetrics.map(m => [m.userCode?.toUpperCase(), m]));
  const sourceSpaceMetricsMap = new Map(sourceSpaceMetrics.map(m => [m.userCode?.toUpperCase(), m]));

  return members.flatMap((member) => {
    const m = spaceMetricsMap.get(member.userCode?.toUpperCase());
    if (m) {
      if (!hasDevelopmentActivity(m)) {
        return [];
      }

      return [{
        member,
        // Performance
        mergedCount: m[SPACE_METRICS.mergedCount] || 0,
        mergedLeadTimeHours: m[SPACE_METRICS.mergedLeadTimeHours] || 0,
        bugCausedCount: m[SPACE_METRICS.bugCausedCount] || 0,
        bugFixLeadTimeHours: m[SPACE_METRICS.bugFixLeadTimeHours] || 0,

        // Activity
        commitCount: m[SPACE_METRICS.commitCount] || 0,
        issueCreatedCount: m[SPACE_METRICS.issueCreatedCount] || 0,
        bugFoundCount: m[SPACE_METRICS.bugFoundCount] || 0,
        bugFixedCount: m[SPACE_METRICS.bugFixedCount] || 0,
        linesAdded: m[SPACE_METRICS.linesAdded] || 0,
        linesDeleted: m[SPACE_METRICS.linesDeleted] || 0,
        linesTotal: m[SPACE_METRICS.linesTotal] || 0,

        // Communication
        reviewedCount: m[SPACE_METRICS.reviewedCount] || 0,
        commentCount: m[SPACE_METRICS.commentCount] || 0,

        // Efficiency
        reviewWaitTime: m[SPACE_METRICS.reviewWaitTime] || 0,
        uninterruptedFocusTimeHours: m[SPACE_METRICS.uninterruptedFocusTimeHours] || 0,
        contextSwitchFrequency: m[SPACE_METRICS.contextSwitchFrequency] || 0,
        reviewCommentCount: m[SPACE_METRICS.reviewCommentCount] || 0,

        // Satisfaction
        satisfactionJobMeaning: m[SPACE_METRICS.satisfactionJobMeaning] || 0,
        satisfactionDeveloperEfficacy: m[SPACE_METRICS.satisfactionDeveloperEfficacy] || 0,
        satisfactionSustainability: m[SPACE_METRICS.satisfactionSustainability] || 0,
        satisfactionImprovementPotential: m[SPACE_METRICS.satisfactionImprovementPotential] || 0,

        // SPACE Scores
        performanceScore: m[SPACE_METRICS.performanceScore] || 0,
        activityScore: m[SPACE_METRICS.activityScore] || 0,
        communicationScore: m[SPACE_METRICS.communicationScore] || 0,
        efficiencyScore: m[SPACE_METRICS.efficiencyScore] || 0,
        satisfactionScore: m[SPACE_METRICS.satisfactionScore] || 0,
        totalScore: m[SPACE_METRICS.spaceTotalScore] || 0,
        hasActivity: true,
        aiEvaluations: m.aiEvaluations || [],
        spaceMetrics: sourceSpaceMetricsMap.get(member.userCode?.toUpperCase()) || m,
      } as DeveloperStat];
    } else {
      return [];
    }
  });
};

export const useDeveloperStats = (projectId: string | null, date: DateRange | undefined, refName?: string) => {
  const { comparison, isLoading, isAiCorrecting, error } = useMetricComparison(projectId, date, refName);
  const developerStats = useMemo(() => !comparison ? [] : buildDeveloperStats(comparison.members,
      comparison.current.members.map(withAiCorrectedScores), comparison.current.members)
      .map(stat => ({ ...stat, trends: comparison.developerTrends[stat.member.userCode.toUpperCase()] })), [comparison]);
  return { developerStats, isLoading, isAiCorrecting, error, comparison };
};
