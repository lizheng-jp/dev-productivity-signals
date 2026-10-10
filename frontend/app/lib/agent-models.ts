export const AGENT_MODELS = [
  'gemini-3.5-flash',
  'gemini-3.6-flash',
  'gemini-3.7-flash',
  'gemini-3.8-flash',
  'gemini-3.5-flash-lite',
] as const;

export type AgentModel = typeof AGENT_MODELS[number];
export const DEFAULT_AGENT_MODEL: AgentModel = 'gemini-3.8-flash';
export const AGENT_MODEL_LABELS: Record<AgentModel, string> = {
  'gemini-3.5-flash': 'Gemini 3.5 Flash',
  'gemini-3.6-flash': 'Gemini 3.6 Flash',
  'gemini-3.7-flash': 'Gemini 3.7 Flash',
  'gemini-3.8-flash': 'Gemini 3.8 Flash',
  'gemini-3.5-flash-lite': 'Gemini 3.5 Flash-Lite',
};

export function isAgentModel(value: string | null): value is AgentModel {
  return AGENT_MODELS.some(model => model === value);
}
