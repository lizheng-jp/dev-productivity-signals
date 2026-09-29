import { useMemo } from 'react';
import { useMetricComparison } from './useMetricComparison';
import { DateRange } from 'react-day-picker';
import { getSpaceMetrics } from '@/lib/api/space-metrics';
import { SPACE_METRICS } from '@/lib/api/space-metrics.constants';
import { ProjectStat } from '@/types/project';
import { Project } from '@/lib/api/projects';

const SCORE_KEYS = [
  SPACE_METRICS.performanceScore,
  SPACE_METRICS.activityScore,
  SPACE_METRICS.communicationScore,
  SPACE_METRICS.efficiencyScore,
  SPACE_METRICS.satisfactionScore,
  SPACE_METRICS.spaceTotalScore,
] as const;

const withAiCorrectedScores = (spaceData: Awaited<ReturnType<typeof getSpaceMetrics>>) => {
  const aiCorrected = spaceData.aiCorrected;
  if (!aiCorrected || typeof aiCorrected !== 'object') {
    return spaceData;
  }

  const correctedSpaceData = { ...spaceData };
  SCORE_KEYS.forEach((key) => {
    const value = (aiCorrected as typeof spaceData)[key];
    if (typeof value === 'number') {
      correctedSpaceData[key] = value;
    }
  });
  return correctedSpaceData;
};

export const buildProjectStat = (
  projectId: string,
  projectName: string,
  spaceData: Awaited<ReturnType<typeof getSpaceMetrics>>,
  sourceSpaceData: Awaited<ReturnType<typeof getSpaceMetrics>> = spaceData,
): ProjectStat => {
  const mergedCount = Number(spaceData[SPACE_METRICS.mergedCount] || 0);
  const mergedLeadTimeHours = Number(spaceData[SPACE_METRICS.mergedLeadTimeHours] || 0);
  const issueCreatedCount = Number(spaceData[SPACE_METRICS.issueCreatedCount] || 0);
  const bugFixedCount = Number(spaceData[SPACE_METRICS.bugFixedCount] || 0);
  const reviewWaitTime = Number(spaceData[SPACE_METRICS.reviewWaitTime] || 0);

  return {
    projectId,
    projectName,
    commitCount: Number(spaceData[SPACE_METRICS.commitCount] || 0),
    totalMergeRequests: mergedCount,
    mergedCount,
    issueCreatedCount,
    bugCausedCount: Number(spaceData[SPACE_METRICS.bugCausedCount] || 0),
    bugFoundCount: Number(spaceData[SPACE_METRICS.bugFoundCount] || 0),
    bugsFixedCount: bugFixedCount,
    linesAdded: spaceData[SPACE_METRICS.linesAdded] || 0,
    linesDeleted: spaceData[SPACE_METRICS.linesDeleted] || 0,
    linesTotal: spaceData[SPACE_METRICS.linesTotal] || 0,

    reviewWaitTime,
    uninterruptedFocusTimeHours: Number(spaceData[SPACE_METRICS.uninterruptedFocusTimeHours] || 0),
    contextSwitchFrequency: Number(spaceData[SPACE_METRICS.contextSwitchFrequency] || 0),
    reviewCommentCount: spaceData[SPACE_METRICS.reviewCommentCount] || 0,
    mergedLeadTimeHours,
    bugFixLeadTimeHours: spaceData[SPACE_METRICS.bugFixLeadTimeHours] || 0,
    reviewedCount: spaceData[SPACE_METRICS.reviewedCount] || 0,
    commentCount: spaceData[SPACE_METRICS.commentCount] || 0,
    satisfactionJobMeaning: spaceData[SPACE_METRICS.satisfactionJobMeaning] || 0,
    satisfactionDeveloperEfficacy: spaceData[SPACE_METRICS.satisfactionDeveloperEfficacy] || 0,
    satisfactionSustainability: spaceData[SPACE_METRICS.satisfactionSustainability] || 0,
    satisfactionImprovementPotential: spaceData[SPACE_METRICS.satisfactionImprovementPotential] || 0,

    // Backward compatibility
    averageHoursToMerge: mergedLeadTimeHours,
    avgHoursToFirstReview: reviewWaitTime,
    issuesCreatedCount: issueCreatedCount,
    issuesCompletedCount: bugFixedCount,

    // SPACE Scores
    performanceScore: spaceData[SPACE_METRICS.performanceScore] || 0,
    activityScore: spaceData[SPACE_METRICS.activityScore] || 0,
    communicationScore: spaceData[SPACE_METRICS.communicationScore] || 0,
    efficiencyScore: spaceData[SPACE_METRICS.efficiencyScore] || 0,
    satisfactionScore: spaceData[SPACE_METRICS.satisfactionScore] || 0,
    totalScore: spaceData[SPACE_METRICS.spaceTotalScore] || 0,
    aiCorrectionReason: sourceSpaceData.aiCorrection?.reason,
  };
};

export const useProjectStats = (projectId: string | null, projects: Project[], date: DateRange | undefined, refName?: string) => {
  const { comparison, isLoading, isAiCorrecting, error } = useMetricComparison(projectId, date, refName);
  const projectStats = useMemo(() => {
    const project = projects.find(p => p.id.toString() === projectId);
    if (!comparison || !project || !projectId) return null;
    const source = comparison.current.project;
    return { ...buildProjectStat(projectId, project.name, withAiCorrectedScores(source), source), trends: comparison.projectTrends, spaceMetrics: source };
  }, [comparison, projectId, projects]);
  return { projectStats, isLoading, isAiCorrecting, error, comparison };
};
