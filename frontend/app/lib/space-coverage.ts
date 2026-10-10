import { spaceGroupsConfig } from '@/lib/space-config';

export const SPACE_DIMENSION_COUNT = spaceGroupsConfig.length;

/**
 * Number of SPACE dimensions that actually have a score. A dimension without data
 * (for example Satisfaction with no survey) is left out of the total, so totals built
 * from different numbers of dimensions are not directly comparable.
 */
export function scoredDimensionCount(metrics: Record<string, unknown> | null | undefined): number {
  if (!metrics) return 0;
  return spaceGroupsConfig.filter(group => typeof metrics[group.scoreKey] === 'number').length;
}
