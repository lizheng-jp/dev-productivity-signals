// frontend/app/lib/api/space-metrics.ts

import { get } from './client';
import {SPACE_METRICS} from './space-metrics.constants';

export interface AiCorrectionExplanation {
  version: string;
  evaluatedMrCount: number;
  neutralMrCount: number;
  effectiveCoefficient: number;
  correctionWeight?: number;
  rawMergedCount: number;
  correctedMergedCount: number;
  evaluationSummary?: string;
  rawMergedCountScore?: number;
  correctedMergedCountScore?: number;
  rawTotalScore?: number;
  correctedTotalScore?: number;
  delta?: number;
  reason: string;
}

export interface SpaceMetricsResponse {
  [SPACE_METRICS.performanceScore]?: number;
  [SPACE_METRICS.activityScore]?: number;
  [SPACE_METRICS.communicationScore]?: number;
  [SPACE_METRICS.efficiencyScore]?: number;
  [SPACE_METRICS.satisfactionScore]?: number;
  [SPACE_METRICS.spaceTotalScore]?: number;
  userCode?: string;
  snapshotId?: string;
  aiCorrected?: SpaceMetricsResponse;
  aiCorrection?: AiCorrectionExplanation;
  aiEvaluations?: unknown[];

  // Included metrics
  [SPACE_METRICS.mergedCount]?: number;
  [SPACE_METRICS.mergedLeadTimeHours]?: number;
  [SPACE_METRICS.issueCreatedCount]?: number;
  [SPACE_METRICS.bugCausedCount]?: number;
  [SPACE_METRICS.bugFixLeadTimeHours]?: number;
  [SPACE_METRICS.commitCount]?: number;
  [SPACE_METRICS.bugFoundCount]?: number;
  [SPACE_METRICS.bugFixedCount]?: number;
  [SPACE_METRICS.linesAdded]?: number;
  [SPACE_METRICS.linesDeleted]?: number;
  [SPACE_METRICS.linesTotal]?: number;
  [SPACE_METRICS.reviewedCount]?: number;
  [SPACE_METRICS.commentCount]?: number;
  [SPACE_METRICS.reviewWaitTime]?: number;
  [SPACE_METRICS.uninterruptedFocusTimeHours]?: number;
  [SPACE_METRICS.contextSwitchFrequency]?: number;
  [SPACE_METRICS.reviewCommentCount]?: number;
  [SPACE_METRICS.satisfactionSurveyScore]?: number;
  [SPACE_METRICS.satisfactionResponseCount]?: number;
  [SPACE_METRICS.satisfactionJobMeaning]?: number;
  [SPACE_METRICS.satisfactionDeveloperEfficacy]?: number;
  [SPACE_METRICS.satisfactionSustainability]?: number;
  [SPACE_METRICS.satisfactionImprovementPotential]?: number;
  [SPACE_METRICS.satisfactionSource]?: 'survey' | 'retention';
  [SPACE_METRICS.contributorRetentionRate]?: number;
  [SPACE_METRICS.retainedContributorCount]?: number;
  [SPACE_METRICS.previousActiveContributorCount]?: number;
  [SPACE_METRICS.hasActivity]?: boolean;

  // Score versions of individual metrics (if needed)
  [key: string]: unknown;
}

export interface GetSpaceMetricsParams {
  since?: string;
  until?: string;
  userName?: string;
  refName?: string;
  aiEnabled?: boolean;
  snapshotId?: string;
}

export async function getSpaceMetrics(projectId: string, params?: GetSpaceMetricsParams): Promise<SpaceMetricsResponse> {
  return get<SpaceMetricsResponse>(`/api/gitlab/projects/${projectId}/space-metrics`, params);
}

export async function getMembersSpaceMetrics(projectId: string, params?: { since?: string; until?: string; refName?: string; aiEnabled?: boolean; snapshotId?: string }): Promise<SpaceMetricsResponse[]> {
  return get<SpaceMetricsResponse[]>(`/api/gitlab/projects/${projectId}/space-metrics/members`, params);
}
