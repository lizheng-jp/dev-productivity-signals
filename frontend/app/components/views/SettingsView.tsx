// components/views/SettingsView.tsx
"use client";
import React, { useState, useCallback } from 'react';
import {
  BarChartHorizontal,
  Bot,
  ExternalLink,
  FolderGit2,
  Languages,
  Plus,
  Shield,
  Settings as SettingsIcon,
  Trash2,
  X,
} from 'lucide-react';
import { GroupsView } from './GroupsView';
import { MetricWeightsView } from './MetricWeightsView';
import { MrAnalysisBatchView } from './MrAnalysisBatchView';
import { useLocale, useTranslations } from 'next-intl';
import { usePathname, useRouter } from '../../../i18n/routing';
import { type Project, resolveGitHubProject } from '@/lib/api/projects';
import { AGENT_MODELS, AGENT_MODEL_LABELS, type AgentModel } from '@/lib/agent-models';


type Props = {
  isOpen: boolean;
  onClose: () => void;
  onSettingsSave: () => void;
  aiEnabled: boolean;
  onAiEnabledChange: (enabled: boolean) => void;
  comparisonDays: number;
  onComparisonDaysChange: (days: number) => void;
  agentModel: AgentModel;
  onAgentModelChange: (model: AgentModel) => void;
  githubProjects: Project[];
  onGitHubProjectsChange: (projects: Project[]) => void;
};

export const SettingsView = ({
  isOpen,
  onClose,
  onSettingsSave,
  aiEnabled,
  onAiEnabledChange,
  comparisonDays,
  onComparisonDaysChange,
  agentModel,
  onAgentModelChange,
  githubProjects,
  onGitHubProjectsChange,
}: Props) => {
  const [activeTab, setActiveTab] = useState<'system' | 'groups' | 'metricWeights' | 'mrAnalysis'>('system');
  const locale = useLocale();
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations('Sidebar');
  const tSettings = useTranslations('Settings');
  const [githubUrl, setGitHubUrl] = useState('');
  const [githubMessage, setGitHubMessage] = useState('');
  const [githubError, setGitHubError] = useState('');
  const [isAddingGitHubProject, setIsAddingGitHubProject] = useState(false);


  const handleSaveSuccess = useCallback(() => {
    onSettingsSave(); // Trigger the refetch in the parent component
  }, [onSettingsSave]);

  const toggleLanguage = () => {
    const nextLocale = locale === 'en' ? 'ja' : 'en';
    router.replace(pathname, { locale: nextLocale });
  };

  const addGitHubProject = async () => {
    if (!githubUrl.trim()) return;
    setGitHubError('');
    setGitHubMessage('');
    setIsAddingGitHubProject(true);
    try {
      const project = await resolveGitHubProject(githubUrl.trim());
      const nextProjects = [
        ...githubProjects.filter(item => item.id !== project.id),
        project,
      ];
      onGitHubProjectsChange(nextProjects);
      setGitHubUrl('');
      setGitHubMessage(tSettings('githubProjectAdded', { name: project.fullName || project.name }));
    } catch (error) {
      setGitHubError(error instanceof Error ? error.message : tSettings('githubConnectionError'));
    } finally {
      setIsAddingGitHubProject(false);
    }
  };

  const removeGitHubProject = (projectId: string) => {
    onGitHubProjectsChange(githubProjects.filter(project => project.id !== projectId));
    setGitHubMessage(tSettings('githubProjectRemoved'));
    setGitHubError('');
  };

  if (!isOpen) return null;

  return (
    <>
      <div
        className="fixed inset-0 bg-slate-900/25 backdrop-blur-sm z-[100] transition-opacity animate-in fade-in duration-300"
        onClick={onClose}
      />

      <div className="fixed inset-0 z-[101] flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-white/90 backdrop-blur-2xl ring-1 ring-slate-900/5 shadow-2xl w-[1100px] max-w-[calc(100vw-2rem)] h-[760px] rounded-2xl flex flex-col border border-slate-200 pointer-events-auto animate-in zoom-in-95 duration-300">
          {/* Header */}
          <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-blue-50 rounded-xl">
                <SettingsIcon className="w-5 h-5 text-blue-600" />
              </div>
              <h2 className="text-xl font-bold text-slate-800">{t('settings')}</h2>
            </div>
            <button
              onClick={onClose}
              className="p-2 hover:bg-slate-100 rounded-full text-slate-400 hover:text-slate-600 transition-all"
            >
              <X className="w-6 h-6" />
            </button>
          </div>

          {/* Tab Navigation */}
          <div className="px-8 py-4 bg-slate-50/50 border-b border-slate-100">
            <div className="flex space-x-1 bg-slate-200/50 p-1.5 rounded-xl w-fit">
              <button
                onClick={() => setActiveTab('system')}
                className={`flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-bold transition-all ${activeTab === 'system'
                    ? 'bg-surface text-blue-700 shadow-md'
                    : 'text-slate-600 hover:text-slate-900'
                  }`}
              >
                <SettingsIcon className="w-4 h-4" />
                {t('settings')}
              </button>
              <button
                onClick={() => setActiveTab('groups')}
                className={`flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-bold transition-all ${activeTab === 'groups'
                    ? 'bg-surface text-blue-700 shadow-md'
                    : 'text-slate-600 hover:text-slate-900'
                  }`}
              >
                <Shield className="w-4 h-4" />
                {t('groups')}
              </button>
              <button
                onClick={() => setActiveTab('metricWeights')}
                className={`flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-bold transition-all ${activeTab === 'metricWeights'
                    ? 'bg-surface text-blue-700 shadow-md'
                    : 'text-slate-600 hover:text-slate-900'
                  }`}
              >
                <BarChartHorizontal className="w-4 h-4" />
                {t('metricWeights')}
              </button>
              <button
                onClick={() => setActiveTab('mrAnalysis')}
                className={`flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-bold transition-all ${activeTab === 'mrAnalysis'
                    ? 'bg-surface text-blue-700 shadow-md'
                    : 'text-slate-600 hover:text-slate-900'
                  }`}
              >
                <Bot className="w-4 h-4" />
                MR AI分析
              </button>
            </div>
          </div>

          {/* Content Area */}
          <div className="flex-1 overflow-y-auto p-8 custom-scrollbar">
            {activeTab === 'system' && (
              <div className="max-w-2xl mx-auto space-y-8 py-4">
                <div className="space-y-6">
                  <section className="space-y-4 border-b border-slate-200 pb-6">
                    <div className="flex items-start gap-3">
                      <div className="rounded-md bg-slate-900 p-2 text-white">
                        <FolderGit2 className="h-4 w-4" />
                      </div>
                      <div>
                        <h3 className="text-sm font-bold text-slate-800">{tSettings('githubIntegration')}</h3>
                        <p className="mt-1 text-xs text-slate-500">{tSettings('githubIntegrationDescription')}</p>
                      </div>
                    </div>

                    <div className="space-y-2">
                      <label htmlFor="github-url" className="text-xs font-bold text-slate-700">
                        {tSettings('githubRepositoryUrl')}
                      </label>
                      <div className="flex gap-2">
                        <input
                          id="github-url"
                          type="url"
                          value={githubUrl}
                          onChange={event => setGitHubUrl(event.target.value)}
                          onKeyDown={event => {
                            if (event.key === 'Enter') void addGitHubProject();
                          }}
                          placeholder="https://github.com/owner/repository"
                          className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
                        />
                        <button
                          type="button"
                          onClick={addGitHubProject}
                          disabled={!githubUrl.trim() || isAddingGitHubProject}
                          className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          <Plus className="h-4 w-4" />
                          {isAddingGitHubProject ? tSettings('adding') : tSettings('addProject')}
                        </button>
                      </div>
                    </div>

                    {githubError && <p className="text-xs font-medium text-red-600">{githubError}</p>}
                    {githubMessage && <p className="text-xs font-medium text-emerald-700">{githubMessage}</p>}

                    {githubProjects.length > 0 && (
                      <div className="divide-y divide-slate-100 rounded-md border border-slate-200">
                        {githubProjects.map(project => (
                          <div key={project.id} className="flex items-center gap-3 px-3 py-2.5">
                            <FolderGit2 className="h-4 w-4 shrink-0 text-slate-600" />
                            <div className="min-w-0 flex-1">
                              <p className="truncate text-sm font-semibold text-slate-800">
                                {project.fullName || project.name}
                              </p>
                            </div>
                            {project.webUrl && (
                              <a
                                href={project.webUrl}
                                target="_blank"
                                rel="noreferrer"
                                title={tSettings('openGitHub')}
                                className="rounded-md p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-800"
                              >
                                <ExternalLink className="h-4 w-4" />
                              </a>
                            )}
                            <button
                              type="button"
                              title={tSettings('removeProject')}
                              onClick={() => removeGitHubProject(project.id)}
                              className="rounded-md p-2 text-slate-500 hover:bg-red-50 hover:text-red-600"
                            >
                              <Trash2 className="h-4 w-4" />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </section>

                  <div className="flex items-center justify-between gap-6 rounded-xl border border-slate-200 bg-surface p-5 shadow-sm">
                    <div>
                      <label htmlFor="ai-enabled" className="text-sm font-bold text-slate-800 cursor-pointer">
                        {tSettings('aiMode')}
                      </label>
                      <p className="text-xs text-slate-500 mt-1">
                        {tSettings('aiModeDescription')}
                      </p>
                    </div>
                    <label htmlFor="ai-enabled" className="relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center">
                      <input
                        type="checkbox"
                        id="ai-enabled"
                        className="peer sr-only"
                        checked={aiEnabled}
                        onChange={(event) => onAiEnabledChange(event.target.checked)}
                      />
                      <span className="absolute inset-0 rounded-full bg-slate-300 transition-colors peer-checked:bg-blue-600 peer-focus-visible:ring-4 peer-focus-visible:ring-blue-500/20" />
                      <span className="absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-transform peer-checked:translate-x-5" />
                    </label>
                  </div>

                  <div className="flex items-center justify-between gap-6 rounded-xl border border-slate-200 bg-surface p-5 shadow-sm">
                    <div>
                      <label htmlFor="comparison-period" className="text-sm font-bold text-slate-800">
                        {tSettings('comparisonPeriod')}
                      </label>
                      <p className="mt-1 text-xs text-slate-500">
                        {tSettings('comparisonPeriodDescription')}
                      </p>
                    </div>
                    <select
                      id="comparison-period"
                      value={comparisonDays}
                      onChange={event => onComparisonDaysChange(Number(event.target.value))}
                      className="rounded-lg border border-slate-200 bg-surface px-3 py-2 text-sm font-bold text-slate-700"
                    >
                      <option value={7}>{tSettings('comparisonWeek')}</option>
                      <option value={30}>{tSettings('comparisonMonth')}</option>
                    </select>
                  </div>

                  {process.env.NEXT_PUBLIC_AGENT_ENABLED === 'true' && (
                    <div className="flex flex-col gap-3 border-t border-slate-200 pt-6 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
                      <div>
                        <label htmlFor="agent-model" className="text-sm font-bold text-slate-800">
                          {tSettings('agentModel')}
                        </label>
                        <p className="mt-1 text-xs text-slate-500">{tSettings('agentModelDescription')}</p>
                      </div>
                      <select id="agent-model" value={agentModel}
                        onChange={event => onAgentModelChange(event.target.value as AgentModel)}
                        className="w-full rounded-md border border-slate-300 bg-surface px-2 py-2 text-sm text-slate-900 sm:w-52 sm:shrink-0">
                        {AGENT_MODELS.map(model => <option key={model} value={model}>{AGENT_MODEL_LABELS[model]}</option>)}
                      </select>
                    </div>
                  )}

                  <div className="flex items-center justify-between gap-6 rounded-xl border border-slate-200 bg-surface p-5 shadow-sm">
                    <div className="flex items-start gap-3">
                      <div className="rounded-lg bg-blue-50 p-2 text-blue-600">
                        <Languages className="h-4 w-4" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-slate-800">{tSettings('language')}</p>
                        <p className="mt-1 text-xs text-slate-500">
                          {tSettings('languageDescription')}
                        </p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={toggleLanguage}
                      className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold text-slate-700 transition hover:bg-slate-50"
                    >
                      {locale === 'en' ? '日本語' : 'English'}
                    </button>
                  </div>

                </div>
              </div>
            )}
            {activeTab === 'groups' && (
              <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
                <GroupsView />
              </div>
            )}
            {activeTab === 'metricWeights' && (
              <MetricWeightsView onSaveSuccess={handleSaveSuccess} />
            )}
            {activeTab === 'mrAnalysis' && (
              <MrAnalysisBatchView />
            )}
          </div>
        </div>
      </div>
    </>
  );
};
