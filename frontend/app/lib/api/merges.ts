// frontend/app/lib/api/merges.ts
import { get } from './client';

export interface MergeRequestStats {
  createdCount: number;
  mergedCount: number;
  totalCommentCount: number;
  totalReviewerCount: number;
  avgHoursToMergeByCreated: number;
  avgHoursToMergeByMerged: number;
  avgHoursToFirstReview: number;
}

export interface MergeRequestActivityPeriod {
  firstMergeCreatedDate?: string | null;
  lastMergeMergedDate?: string | null;
  periodStart: string;
  periodEnd: string;
}

export const getMergeRequestStats = (
  projectId: string,
  since: string,
  until: string,
  userName?: string,
  refName?: string
): Promise<MergeRequestStats> => {
  const params: Record<string, string> = { since, until };
  if (userName) params.userName = userName;
  if (refName) params.refName = refName;

  return get<MergeRequestStats>(`/api/projects/${projectId}/merge_requests/stats`, params);
};

export const getMergeRequestActivityPeriod = (
  projectId: string,
  refName?: string,
  userName?: string
): Promise<MergeRequestActivityPeriod> => {
  const params: Record<string, string> = {};
  if (refName) params.refName = refName;
  if (userName) params.userName = userName;

  return get<MergeRequestActivityPeriod>(
    `/api/projects/${projectId}/merge_requests/activity-period`,
    Object.keys(params).length > 0 ? params : undefined
  );
};
