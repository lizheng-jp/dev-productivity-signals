// frontend/app/lib/api/projects.ts
import { get, post } from './client';

export interface Member {
  id: number;
  name: string;
  username: string;
  avatar_url: string;
}

export interface Project {
  id: string;
  name: string;
  description?: string;
  defaultBranch?: string;
  provider?: 'mock' | 'gitlab' | 'github';
  fullName?: string;
  webUrl?: string;
}

export interface Branch {
  name: string;
  merged: boolean;
  protected: boolean;
  default: boolean;
  web_url: string;
  first_commit_at?: string;
  last_commit_at?: string;
}

export const getProjects = (): Promise<Project[]> => {
  return get<Project[]>('/api/gitlab/projects');
};

export const getBranches = (projectId: string): Promise<Branch[]> => {
  return get<Branch[]>(`/api/gitlab/projects/${projectId}/repository/branches`);
};

export const resolveGitHubProject = (repositoryUrl: string): Promise<Project> => {
  return post<Project>('/api/github/projects/resolve', { repositoryUrl });
};

export interface GitHubRateLimit {
  authenticated: boolean;
  limit?: number;
  remaining?: number;
  reset?: number;
}

export const getGitHubRateLimit = (): Promise<GitHubRateLimit> => {
  return get<GitHubRateLimit>('/api/github/rate-limit');
};

const GITHUB_PROJECTS_STORAGE_KEY = 'signals.githubProjects';

// Always listed for every visitor and cannot be removed in Settings.
export const DEFAULT_GITHUB_PROJECTS: Project[] = [
  {
    id: 'github~lizheng-jp~dev-productivity-signals',
    name: 'dev-productivity-signals',
    fullName: 'lizheng-jp/dev-productivity-signals',
    description: 'Dev Productivity Signals — engineering metrics and evidence-grounded analysis with Signals Agent.',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/lizheng-jp/dev-productivity-signals',
  },
  {
    id: 'github~google~gson',
    name: 'gson',
    fullName: 'google/gson',
    description: 'A Java serialization/deserialization library to convert Java Objects into JSON and back',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/google/gson',
  },
  {
    id: 'github~anthropics~claude-code',
    name: 'claude-code',
    fullName: 'anthropics/claude-code',
    description: 'Claude Code is an agentic coding tool that lives in your terminal.',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/anthropics/claude-code',
  },
  {
    id: 'github~google-gemini~gemini-cli',
    name: 'gemini-cli',
    fullName: 'google-gemini/gemini-cli',
    description: 'An open-source AI agent that brings the power of Gemini directly into your terminal.',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/google-gemini/gemini-cli',
  },
  {
    id: 'github~openai~openai-agents-python',
    name: 'openai-agents-python',
    fullName: 'openai/openai-agents-python',
    description: 'A lightweight, powerful framework for multi-agent workflows',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/openai/openai-agents-python',
  },
  {
    id: 'github~ollama~ollama',
    name: 'ollama',
    fullName: 'ollama/ollama',
    description: 'Get up and running with Kimi, GLM, MiniMax, DeepSeek, gpt-oss, Qwen, Gemma and other models.',
    defaultBranch: 'main',
    provider: 'github',
    webUrl: 'https://github.com/ollama/ollama',
  },
];

export const isDefaultGitHubProject = (projectId: string) =>
  DEFAULT_GITHUB_PROJECTS.some(project => project.id === projectId);

export const withDefaultGitHubProjects = (projects: Project[]): Project[] => [
  ...DEFAULT_GITHUB_PROJECTS,
  ...projects.filter(project => !isDefaultGitHubProject(project.id)),
];

const readStoredGitHubProjects = (): Project[] => {
  if (typeof window === 'undefined') return [];
  try {
    const value = JSON.parse(window.localStorage.getItem(GITHUB_PROJECTS_STORAGE_KEY) || '[]');
    if (!Array.isArray(value)) return [];
    return value.filter((project): project is Project => (
      project != null
      && typeof project.id === 'string'
      && project.id.startsWith('github~')
      && typeof project.name === 'string'
    ));
  } catch {
    return [];
  }
};

/** User-added GitHub projects only; the defaults are not part of this list. */
export const hasUserGitHubProjects = () =>
  readStoredGitHubProjects().some(project => !isDefaultGitHubProject(project.id));

export const loadStoredGitHubProjects = (): Project[] => withDefaultGitHubProjects(readStoredGitHubProjects());

export const saveStoredGitHubProjects = (projects: Project[]) => {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(
    GITHUB_PROJECTS_STORAGE_KEY,
    JSON.stringify(projects.filter(project => project.provider === 'github' && !isDefaultGitHubProject(project.id))),
  );
};
