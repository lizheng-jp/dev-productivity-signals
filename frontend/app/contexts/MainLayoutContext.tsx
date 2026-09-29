"use client";

import { createContext, useContext, Dispatch, SetStateAction } from 'react';
import { DateRange } from 'react-day-picker';
import { Project } from '@/lib/api/projects';
import { MetricWeight } from '@/lib/api/metric-weights';
import type { ProjectMember } from '@/lib/api/members';
import type { SpaceMetricsResponse } from '@/lib/api/space-metrics';
import type { DeveloperStat } from '@/types/developer';
import type { ProjectStat } from '@/types/project';

export interface ComparisonSeed {
    projectId: string;
    userCode: string;
    member: ProjectMember;
    since: string;
    until: string;
    refName?: string;
    spaceMetrics: SpaceMetricsResponse;
}

export interface DashboardStatsState {
    developerStats: DeveloperStat[];
    projectStats: ProjectStat | null;
    isDeveloperLoading: boolean;
    isProjectLoading: boolean;
    isDeveloperAiCorrecting: boolean;
    isProjectAiCorrecting: boolean;
}

interface MainLayoutContextType {
    // Data
    projects: Project[];
    isLoadingProjects: boolean;
    errorProjects: string | null;
    metricConfigs: MetricWeight[];

    // Filters
    selectedProjectId: string | null;
    setSelectedProjectId: Dispatch<SetStateAction<string | null>>;
    selectedBranch: string;
    setSelectedBranch: Dispatch<SetStateAction<string>>;
    date: DateRange | undefined;
    setDate: Dispatch<SetStateAction<DateRange | undefined>>;
    aiEnabled: boolean;
    setAiEnabled: Dispatch<SetStateAction<boolean>>;
    comparisonSeed: ComparisonSeed | null;
    setComparisonSeed: Dispatch<SetStateAction<ComparisonSeed | null>>;
    dashboardStats: DashboardStatsState;
    setDashboardStats: Dispatch<SetStateAction<DashboardStatsState>>;
    comparisonDays: number;
    setComparisonDays: Dispatch<SetStateAction<number>>;

    // Actions
    fetchMetricConfigs: () => Promise<void>;
}

const MainLayoutContext = createContext<MainLayoutContextType | null>(null);

export const MainLayoutProvider = MainLayoutContext.Provider;

export const useMainLayout = (): MainLayoutContextType => {
    const context = useContext(MainLayoutContext);
    if (!context) {
        throw new Error('useMainLayout must be used within a MainLayoutProvider');
    }
    return context;
};
