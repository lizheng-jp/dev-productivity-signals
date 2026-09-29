// frontend/app/lib/api/members.ts
import { get } from './client';

export interface ProjectMember {
  userCode: string;
  userName?: string | null;
  groupId?: string | null;
  groupName?: string | null;
}

export const getProjectMembers = (projectId: string): Promise<ProjectMember[]> => {
  return get<ProjectMember[]>(`/api/gitlab/projects/${projectId}/members`);
};

export const getActiveProjectMembers = (
  projectId: string,
  params: { since: string; until: string; refName?: string }
): Promise<ProjectMember[]> => {
  return get<ProjectMember[]>(`/api/gitlab/projects/${projectId}/active-members`, params);
};
