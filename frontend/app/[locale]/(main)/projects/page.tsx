"use client";

import { useMainLayout } from '@/contexts/MainLayoutContext';
import { ProjectPortfolioView } from '@/components/views/ProjectPortfolioView';
import { useRouter } from '../../../../i18n/routing';
import type { Project } from '@/lib/api/projects';

export default function ProjectPortfolioPage() {
  const { projects, date, setSelectedProjectId } = useMainLayout();
  const router = useRouter();
  const githubProjects = projects.filter(project => project.provider === 'github');

  const openProject = (project: Project) => {
    setSelectedProjectId(project.id);
    router.push('/project');
  };

  return <ProjectPortfolioView projects={githubProjects} date={date} onOpenProject={openProject} />;
}
