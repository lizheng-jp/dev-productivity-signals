import { get, post } from './client';

export interface AiPromptVersion {
  id: number;
  versionKey: string;
  name?: string | null;
  model: string;
  promptHash: string;
  active: boolean;
  createdBy?: string | null;
  createdAt: string;
}

export interface AiMrAnalysisJob {
  id: number;
  projectId: string;
  refName?: string | null;
  sinceDate: string;
  untilDate: string;
  promptVersionId: number;
  model: string;
  status: string;
  targetCount: number;
  analyzedCount: number;
  skippedCount: number;
  failedCount: number;
  requestedBy?: string | null;
  requestedAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  errorMessage?: string | null;
}

export interface AiMrAnalysisJobItem {
  id: number;
  jobId: number;
  projectId: string;
  mrIid: number;
  authorUsername?: string | null;
  sourceBranch?: string | null;
  targetBranch?: string | null;
  mergedAt?: string | null;
  status: string;
  evaluationId?: number | null;
  skipReason?: string | null;
  errorMessage?: string | null;
  diffLineCount?: number | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface AiMrAnalysisJobRequest {
  projectId: string;
  refName?: string;
  sinceDate: string;
  untilDate: string;
  promptVersionId?: number;
  requestedBy?: string;
}

export interface AiMrAnalysisSettings {
  manualRunEnabled: boolean;
}

export const getMrAnalysisSettings = (): Promise<AiMrAnalysisSettings> => {
  return get<AiMrAnalysisSettings>('/api/ai/mr-analysis-settings');
};

export const getPromptVersions = (): Promise<AiPromptVersion[]> => {
  return get<AiPromptVersion[]>('/api/ai/prompt-versions');
};

export const getMrAnalysisJobs = (): Promise<AiMrAnalysisJob[]> => {
  return get<AiMrAnalysisJob[]>('/api/ai/mr-analysis-jobs');
};

export const runMrAnalysisJob = (request: AiMrAnalysisJobRequest): Promise<AiMrAnalysisJob> => {
  return post<AiMrAnalysisJob>('/api/ai/mr-analysis-jobs', request);
};

export const getMrAnalysisJobItems = (jobId: number): Promise<AiMrAnalysisJobItem[]> => {
  return get<AiMrAnalysisJobItem[]>(`/api/ai/mr-analysis-jobs/${jobId}/items`);
};
