/**
 * Keeps AI-generated evaluation text concise without changing the stored
 * evaluation result or the prompt's machine-readable tag format.
 */
export const formatAiEvaluationText = (text: string) => text
  .replace(/【関連SPACE：([^／】]+)／関連指標：([^】]+)】/g, '【$1／$2】')
  .replace(/MR件数スコアを[0-9.]+点から[0-9.]+点に補正し、/g, '')
  .replace(/残存bug/g, '潜在的な不具合');
