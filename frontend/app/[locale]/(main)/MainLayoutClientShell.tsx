"use client";

import React, { useState, useCallback, useEffect } from 'react';
import { addDays } from "date-fns"
import { Sidebar } from '@/components/layout/Sidebar';
import { Header } from '@/components/layout/Header';
import { SettingsView } from '@/components/views/SettingsView';
import { AgentView } from '@/components/views/AgentView';
import { DEFAULT_AGENT_MODEL, isAgentModel, type AgentModel } from '@/lib/agent-models';
import {
    getProjects,
    loadStoredGitHubProjects,
    Project,
    saveStoredGitHubProjects,
} from '@/lib/api/projects';
import { MetricWeight } from '@/lib/api/metric-weights';
import { MainLayoutProvider, type ComparisonSeed, type DashboardStatsState } from '@/contexts/MainLayoutContext';
import { DateRange } from 'react-day-picker';
import { usePathname } from 'next/navigation';

interface MainLayoutClientShellProps {
    children: React.ReactNode;
}

export function MainLayoutClientShell({ children }: MainLayoutClientShellProps) {
    const pathname = usePathname();
    const isComparisonPage = pathname.includes('/comparison');
    const isFeedbackPage = pathname.includes('/feedback');
    const isManualPage = pathname.includes('/manual');

    // State for UI panels
    const [isSettingsOpen, setIsSettingsOpen] = useState(false);
    const [comparisonDays, setComparisonDays] = useState(7);
    const [agentModel, setAgentModel] = useState<AgentModel>(() => {
        if (typeof window === 'undefined') return DEFAULT_AGENT_MODEL;
        const saved = window.localStorage.getItem('signals.agentModel');
        return isAgentModel(saved) ? saved : DEFAULT_AGENT_MODEL;
    });
    const [aiEnabled, setAiEnabled] = useState(() => {
        if (typeof window === 'undefined') return true;
        const saved = window.localStorage.getItem('signals.aiEnabled');
        return saved === null ? true : saved === 'true';
    });

    useEffect(() => {
        window.localStorage.setItem('signals.aiEnabled', String(aiEnabled));
    }, [aiEnabled]);

    useEffect(() => {
        window.localStorage.setItem('signals.agentModel', agentModel);
    }, [agentModel]);

    // Global filters render immediately; project data is loaded after hydration.
    const [projects, setProjects] = useState<Project[]>([]);
    const [githubProjects, setGitHubProjects] = useState<Project[]>([]);
    const [isLoadingProjects, setIsLoadingProjects] = useState(true);
    const [errorProjects, setErrorProjects] = useState<string | null>(null);
    const [metricConfigs, setMetricConfigs] = useState<MetricWeight[]>([]);
    const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);
    const [selectedBranch, setSelectedBranch] = useState('');
    const [date, setDate] = React.useState<DateRange | undefined>({
        from: addDays(new Date(), -30),
        to: new Date(),
    });
    const [comparisonSeed, setComparisonSeed] = useState<ComparisonSeed | null>(null);
    const [dashboardStats, setDashboardStats] = useState<DashboardStatsState>({
        developerStats: [],
        projectStats: null,
        isDeveloperLoading: false,
        isProjectLoading: false,
        isDeveloperAiCorrecting: false,
        isProjectAiCorrecting: false,
    });

    useEffect(() => {
        let ignore = false;

        const loadProjects = async () => {
            const storedGitHubProjects = loadStoredGitHubProjects();
            if (storedGitHubProjects.length > 0) {
                setGitHubProjects(storedGitHubProjects);
                setProjects(storedGitHubProjects);
                const firstProject = storedGitHubProjects[0];
                setSelectedProjectId(firstProject.id);
                setSelectedBranch(firstProject.defaultBranch || '');
                setIsLoadingProjects(false);
            }
            try {
                const loadedProjects = await getProjects();
                if (ignore) return;
                const loadedIds = new Set(loadedProjects.map(project => project.id));
                const mergedProjects = [
                    ...loadedProjects,
                    ...storedGitHubProjects.filter(project => !loadedIds.has(project.id)),
                ];
                setGitHubProjects(storedGitHubProjects);
                setProjects(mergedProjects);
                setErrorProjects(null);

                const firstProject = mergedProjects[0];
                setSelectedProjectId(firstProject ? firstProject.id.toString() : null);
                setSelectedBranch(firstProject?.defaultBranch || '');
            } catch (error) {
                if (ignore) return;
                console.error('Failed to load projects:', error);
                if (storedGitHubProjects.length === 0) {
                    setProjects([]);
                    setSelectedProjectId(null);
                    setSelectedBranch('');
                    setErrorProjects(error instanceof Error ? error.message : 'Failed to load projects');
                }
            } finally {
                if (!ignore) setIsLoadingProjects(false);
            }
        };

        void loadProjects();
        return () => {
            ignore = true;
        };
    }, []);

    const handleGitHubProjectsChange = useCallback((nextProjects: Project[]) => {
        saveStoredGitHubProjects(nextProjects);
        setGitHubProjects(nextProjects);
        setProjects(currentProjects => {
            const baseProjects = currentProjects.filter(project => project.provider !== 'github');
            return [...baseProjects, ...nextProjects];
        });
        const selectedProjectWasRemoved = selectedProjectId?.startsWith('github~')
            && !nextProjects.some(project => project.id === selectedProjectId);
        if (selectedProjectWasRemoved) {
            setSelectedProjectId(null);
            setSelectedBranch('');
        }
    }, [selectedProjectId]);

    const handleProjectChange = useCallback<React.Dispatch<React.SetStateAction<string | null>>>((value) => {
        const nextProjectId = typeof value === 'function' ? value(selectedProjectId) : value;
        const nextProject = projects.find(project => project.id.toString() === nextProjectId);
        setSelectedProjectId(nextProjectId);
        setSelectedBranch(nextProject?.defaultBranch || '');
    }, [projects, selectedProjectId]);

    // This function can still exist for client-side refetching if needed
    const fetchMetricConfigs = useCallback(async () => {
        try {
            const { getMetricWeights } = await import('@/lib/api/metric-weights');
            const configs = await getMetricWeights();
            setMetricConfigs(configs);
        } catch (error) {
            console.error("Failed to refetch metric configs:", error);
        }
    }, []);

    const contextValue = {
        projects,
        isLoadingProjects,
        errorProjects,
        metricConfigs,
        selectedProjectId,
        setSelectedProjectId: handleProjectChange,
        selectedBranch,
        setSelectedBranch,
        date,
        setDate,
        aiEnabled,
        setAiEnabled,
        comparisonSeed,
        setComparisonSeed,
        dashboardStats,
        setDashboardStats,
        comparisonDays,
        setComparisonDays,
        fetchMetricConfigs,
    };

    return (
        <MainLayoutProvider value={contextValue}>
            <div className="min-h-screen bg-slate-100 font-sans text-slate-900 flex">
                <Sidebar onSettingsClick={() => setIsSettingsOpen(true)} />

                <div className="flex min-w-0 flex-1 md:ml-64">
                    <div className="flex-1 flex flex-col min-w-0 h-screen overflow-hidden">
                        {!isComparisonPage && !isFeedbackPage && !isManualPage && (
                            <Header
                                projects={projects}
                                isLoadingProjects={isLoadingProjects}
                                errorProjects={errorProjects}
                                selectedProjectId={selectedProjectId}
                                onProjectChange={handleProjectChange}
                                selectedBranch={selectedBranch}
                                onBranchChange={setSelectedBranch}
                                date={date}
                                setDate={setDate}
                            />
                        )}

                        <main className="flex-1 overflow-y-auto bg-slate-100 p-4 pb-24 md:p-8">
                            <div className="max-w-7xl mx-auto">{children}</div>
                        </main>
                    </div>
                </div>

                <SettingsView
                    isOpen={isSettingsOpen}
                    onClose={() => setIsSettingsOpen(false)}
                    onSettingsSave={fetchMetricConfigs}
                    aiEnabled={aiEnabled}
                    onAiEnabledChange={setAiEnabled}
                    comparisonDays={comparisonDays}
                    onComparisonDaysChange={setComparisonDays}
                    agentModel={agentModel}
                    onAgentModelChange={setAgentModel}
                    githubProjects={githubProjects}
                    onGitHubProjectsChange={handleGitHubProjectsChange}
                />
                {process.env.NEXT_PUBLIC_AGENT_ENABLED === 'true' && (
                    <AgentView onOpenSettings={() => setIsSettingsOpen(true)}
                        model={agentModel} />
                )}
            </div>
        </MainLayoutProvider>
    );
}
