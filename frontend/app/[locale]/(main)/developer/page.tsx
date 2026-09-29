"use client";

import { useState } from 'react';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { useDeveloperStats } from '@/lib/hooks/useDeveloperStats';
import { DeveloperAnalyticsView } from '@/components/views/DeveloperAnalyticsView';
import { DeveloperDetailPanel } from '@/components/views/DeveloperDetailPanel';
import type { DeveloperStat } from '@/types/developer';

export default function DeveloperAnalyticsPage() {
  const { selectedProjectId, selectedBranch, date, metricConfigs } = useMainLayout();
  const { developerStats, isLoading } = useDeveloperStats(selectedProjectId, date, selectedBranch || undefined);
  const [selectedDeveloper, setSelectedDeveloper] = useState<DeveloperStat | null>(null);

  return (
    <>
      <DeveloperDetailPanel
        isOpen={selectedDeveloper !== null}
        onClose={() => setSelectedDeveloper(null)}
        developer={selectedDeveloper}
        metricConfigs={metricConfigs}
      />
      <DeveloperAnalyticsView
        developerStats={developerStats}
        isLoading={isLoading}
        onViewDetails={setSelectedDeveloper}
      />
    </>
  );
}
