import { get, post } from './client';
import { SpaceMetricsResponse } from './space-metrics';


export interface AiEvaluation {
  evaluationId?: string;
  strengths: string[];
  weaknesses: string[];
  suggestions: string[];
}

export interface ComparisonAnalysisResponse {
  spaceMetrics: SpaceMetricsResponse;
  aiEvaluation: AiEvaluation | null;
}

export interface AiAnalysisProgress {
  total: number;
  current: number;
  processed: number;
  currentMrIid?: number | null;
  status: 'idle' | 'running' | 'completed' | string;
  message?: string | null;
}

export interface evaluatePerformanceParams {
  projectId: string;
  since?: string;
  until?: string;
  userName?: string;
  refName?: string;
  aiEnabled?: boolean;
  snapshotId?: string;
}

 export const evaluatePerformance = (params: evaluatePerformanceParams): Promise<AiEvaluation> => {
    return get<AiEvaluation>(`/api/ai/evaluate`, params);
}

export const getComparisonAnalysis = (params: evaluatePerformanceParams): Promise<ComparisonAnalysisResponse> => {
  return get<ComparisonAnalysisResponse>('/api/ai/comparison-analysis', params);
}

export const getAiAnalysisProgress = (params: evaluatePerformanceParams): Promise<AiAnalysisProgress> => {
  return get<AiAnalysisProgress>('/api/ai/analysis-progress', params);
}

export type EvaluationSection = 'strengths' | 'weaknesses' | 'suggestions';
export interface EvaluationFeedback { section: EvaluationSection; itemIndex: number; helpful: boolean }
export const getEvaluationFeedback = (evaluationId: string) => get<EvaluationFeedback[]>('/api/ai/evaluation-feedback', { evaluationId });
export const saveEvaluationFeedback = (evaluationId: string, section: EvaluationSection, itemIndex: number, helpful: boolean) =>
  post<EvaluationFeedback>('/api/ai/evaluation-feedback', { evaluationId, section, itemIndex, helpful });
