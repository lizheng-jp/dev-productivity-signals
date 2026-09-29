// frontend/app/lib/api/metric-weights.ts
import { get } from './client';
import { put } from './client';

export interface MetricWeight {
    id: number;
    metricKey: string;
    parentKey: string | null;
    weight: number;
    active: boolean;
    minThreshold: number | null;
    maxThreshold: number | null;
    positiveMetric: boolean;
    description: string;
}

export const getMetricWeights = async (): Promise<MetricWeight[]> => {
    try {
        const response = await get<MetricWeight[]>('/api/metric-weights');
        return response;
    } catch (error) {
        console.error('Error fetching metric weights:', error);
        throw error;
    }
};

export const updateMetricWeights = async (weights: MetricWeight[]): Promise<MetricWeight[]> => {
    try {
        const response = await put<MetricWeight[]>('/api/metric-weights', weights);
        return response;
    } catch (error) {
        console.error('Error updating metric weights:', error);
        throw error;
    }
};
