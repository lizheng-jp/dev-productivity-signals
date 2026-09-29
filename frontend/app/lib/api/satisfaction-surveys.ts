import { get, post } from './client';

export type SatisfactionQuestionKey =
  | 'q1WorkValue'
  | 'q2WorkMeaning'
  | 'q3TeamSatisfaction'
  | 'q4RecommendTeam'
  | 'q5InformationAccess'
  | 'q6EnvironmentSupport'
  | 'q7SupportFlow'
  | 'q8OutcomeConfidence'
  | 'q9SustainableWorkload'
  | 'q10Fatigue'
  | 'q11Detachment'
  | 'q12Pressure'
  | 'q13PsychologicalSafety'
  | 'q14ImprovementExpectation';

export type ProjectSatisfactionSurveyRequest = Record<SatisfactionQuestionKey, number> & {
  userCode: string;
  userName?: string;
  surveyDate?: string;
  periodStart?: string;
  periodEnd?: string;
  respondentName?: string;
  comment?: string;
};

export type ProjectSatisfactionSurvey = ProjectSatisfactionSurveyRequest & {
  id: number;
  projectId: string;
  responseScore: number;
  createdAt: string;
};

export type ProjectSatisfactionSummary = Partial<Record<SatisfactionQuestionKey, number>> & {
  projectId: string;
  userCode?: string | null;
  userName?: string | null;
  responseCount: number;
  satisfactionScore?: number | null;
  s1JobSatisfaction?: number | null;
  s2DeveloperEfficacy?: number | null;
  s3Sustainability?: number | null;
  s4ImprovementPotential?: number | null;
};

export const getProjectSatisfactionSurveys = (
  projectId: string,
  params?: { since?: string; until?: string; userName?: string }
): Promise<ProjectSatisfactionSurvey[]> => {
  return get<ProjectSatisfactionSurvey[]>(`/api/projects/${projectId}/satisfaction-surveys`, params);
};

export const getProjectSatisfactionSummary = (
  projectId: string,
  params?: { since?: string; until?: string; userName?: string }
): Promise<ProjectSatisfactionSummary> => {
  return get<ProjectSatisfactionSummary>(`/api/projects/${projectId}/satisfaction-surveys/summary`, params);
};

export const createProjectSatisfactionSurvey = (
  projectId: string,
  body: ProjectSatisfactionSurveyRequest
): Promise<ProjectSatisfactionSurvey> => {
  return post<ProjectSatisfactionSurvey>(`/api/projects/${projectId}/satisfaction-surveys`, body);
};
