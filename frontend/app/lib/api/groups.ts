import { get, post } from './client';

export interface GroupUser {
  userCode: string;
  userName: string;
}

export interface Group {
  groupId: string;
  groupName: string;
  users: GroupUser[];
}

export const getGroups = async (): Promise<Group[]> => {
  return get<Group[]>('/api/groups');
};

export const createGroup = async (group: { groupId: string; groupName: string }): Promise<Group> => {
  return post<Group>('/api/groups', group);
};

export const addGroupUser = async (groupId: string, user: GroupUser): Promise<GroupUser> => {
  return post<GroupUser>(`/api/groups/${groupId}/users`, user);
};

export const deleteGroup = async (groupId: string): Promise<void> => {
  return post<void>(`/api/groups/${groupId}/delete`, { groupId });
};

export const removeGroupUser = async (groupId: string, userCode: string): Promise<void> => {
  return post<void>(`/api/groups/${groupId}/users/${userCode}/delete`, { groupId, userCode });
};
