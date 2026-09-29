"use client";

import React from 'react';
import { DashboardView } from '@/components/views/DashboardView';
import { useDeveloperStats } from '@/lib/hooks/useDeveloperStats';
import { useProjectStats } from '@/lib/hooks/useProjectStats';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { formatLocalDate } from '@/lib/date-format';

export default function DashboardPage() {
  const {
    projects,
    isLoadingProjects,
    errorProjects,
    metricConfigs,
    selectedProjectId,
    selectedBranch,
    date,
    setComparisonSeed,
    setDashboardStats,
    fetchMetricConfigs,
  } = useMainLayout();

  const {
    developerStats,
    isLoading: isDevLoading,
    isAiCorrecting: isDeveloperAiCorrecting,
  } = useDeveloperStats(selectedProjectId, date, selectedBranch || undefined);
  const {
    projectStats,
    isLoading: isProjectLoading,
    isAiCorrecting: isProjectAiCorrecting,
    comparison,
    error,
  } = useProjectStats(selectedProjectId, projects, date, selectedBranch || undefined);

  const since = formatLocalDate(date?.from);
  const until = formatLocalDate(date?.to);

  React.useEffect(() => {
    setDashboardStats({
      developerStats,
      projectStats,
      isDeveloperLoading: isDevLoading,
      isProjectLoading,
      isDeveloperAiCorrecting,
      isProjectAiCorrecting,
    });
  }, [
    developerStats,
    projectStats,
    isDevLoading,
    isProjectLoading,
    isDeveloperAiCorrecting,
    isProjectAiCorrecting,
    setDashboardStats,
  ]);

  React.useEffect(() => {
    if (isDevLoading || !selectedProjectId || !since || !until || developerStats.length === 0) {
      setComparisonSeed(null);
      return;
    }

    const topDeveloper = developerStats.reduce((best, current) =>
      (current.totalScore || 0) > (best.totalScore || 0) ? current : best
    );
    if (!topDeveloper.spaceMetrics) {
      setComparisonSeed(null);
      return;
    }

    setComparisonSeed({
      projectId: selectedProjectId,
      userCode: topDeveloper.member.userCode,
      member: topDeveloper.member,
      since,
      until,
      refName: selectedBranch || undefined,
      spaceMetrics: topDeveloper.spaceMetrics,
    });
  }, [developerStats, isDevLoading, selectedProjectId, selectedBranch, since, until, setComparisonSeed]);

  return (
    <DashboardView
      comparison={comparison}
      error={error}
      projects={projects}
      isLoadingProjects={isLoadingProjects}
      errorProjects={errorProjects}
      selectedProjectId={selectedProjectId}
      date={date}
      developerStats={developerStats}
      isDevLoading={isDevLoading}
      projectStats={projectStats}
      isProjectLoading={isProjectLoading}
      isAiCorrecting={isDeveloperAiCorrecting || isProjectAiCorrecting}
      metricConfigs={metricConfigs}
      onMetricConfigsNeeded={fetchMetricConfigs}
    />
  );
}
