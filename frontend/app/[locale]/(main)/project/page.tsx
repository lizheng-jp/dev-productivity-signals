"use client";

import { useMainLayout } from '@/contexts/MainLayoutContext';
import { useProjectStats } from '@/lib/hooks/useProjectStats';
import { ProjectAnalyticsView } from '@/components/views/ProjectAnalyticsView';

export default function ProjectAnalyticsPage() {
  const { projects, selectedProjectId, selectedBranch, date } = useMainLayout();
  const { projectStats, isLoading, isAiCorrecting } = useProjectStats(selectedProjectId, projects, date, selectedBranch || undefined);

  return (
    <ProjectAnalyticsView
      projects={projects}
      projectStats={projectStats}
      isLoading={isLoading}
      isAiCorrecting={isAiCorrecting}
      currentDate={date}
    />
  );
}
