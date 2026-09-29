// frontend/app/lib/api/issues.ts
import { get } from './client';

export interface IssueStats {
  createdCount: number;
  completedCount: number;
  bugFoundCount: number;
  bugCausedCount: number;
  bugFixCount: number;
  avgBugFixedHours: number;
}

export const getIssueStats = (
  projectId: string,
  since?: string,
  until?: string,
  userName?: string,
  refName?: string
): Promise<IssueStats> => {
  const params: Record<string, any> = {};
  if (since) params.since = since;
  if (until) params.until = until;
  if (userName) params.userName = userName;
  if (refName) params.refName = refName;

  return get<IssueStats>(`/api/gitlab/projects/${projectId}/issues/stats`, params);
};
