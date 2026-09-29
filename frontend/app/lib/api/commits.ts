// frontend/app/lib/api/commits.ts
import { get } from './client';

export interface WeeklyCommitCount {
  weekStart: string; // YYYY-MM-DD
  weekEnd: string;   // YYYY-MM-DD
  count: number;
}

export interface CommitCount {
  totalCommitCount: number;
  weeklyBreakdown: WeeklyCommitCount[];
  commitStatus?: {
    linesAdded: number;
    linesDeleted: number;
    linesTotal: number;
  }
}

export const getCommitCount = (
  projectId: string,
  since?: string,
  until?: string,
  author?: string,
  refName?: string,
): Promise<CommitCount> => {
  const params: Record<string, any> = {};
  if (since) params.since = since;
  if (until) params.until = until;
  if (author) params.author = author;
  if (refName) params.refName = refName;

  return get<CommitCount>(`/api/gitlab/projects/${projectId}/commits/count`, params);
};
