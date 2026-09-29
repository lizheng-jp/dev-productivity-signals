"use client";

import { useMainLayout } from '@/contexts/MainLayoutContext';
import { useDeveloperStats } from '@/lib/hooks/useDeveloperStats';
import { TeamAnalyticsView } from '@/components/views/TeamAnalyticsView';

export default function TeamAnalyticsPage() {
  const { selectedProjectId, selectedBranch, date } = useMainLayout();
  const { developerStats, isLoading } = useDeveloperStats(selectedProjectId, date, selectedBranch || undefined);

  return (
    <TeamAnalyticsView
      developerStats={developerStats}
      isLoadingStats={isLoading}
    />
  );
}
