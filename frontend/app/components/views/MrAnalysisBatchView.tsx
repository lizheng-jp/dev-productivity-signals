"use client";

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ChevronDown, FolderKanban, Loader2, Play, RefreshCw, Search } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/components/ui/Common';
import { getBranches, getProjects, Branch, Project } from '@/lib/api/projects';
import { getMergeRequestActivityPeriod } from '@/lib/api/merges';
import {
  AiMrAnalysisJob,
  AiMrAnalysisJobItem,
  AiPromptVersion,
  getMrAnalysisJobItems,
  getMrAnalysisJobs,
  getMrAnalysisSettings,
  getPromptVersions,
  runMrAnalysisJob,
} from '@/lib/api/mr-analysis';

const dateOnly = (value?: string | null) => (value ? value.slice(0, 10) : '');

const formatDateTime = (value?: string | null) => {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 19);
};

const latestBranch = (branches: Branch[]) => {
  return [...branches].sort((a, b) => {
    const aTime = new Date(a.last_commit_at || 0).getTime();
    const bTime = new Date(b.last_commit_at || 0).getTime();
    if (aTime !== bTime) return bTime - aTime;
    if (a.default !== b.default) return a.default ? -1 : 1;
    return a.name.localeCompare(b.name);
  })[0];
};

export const MrAnalysisBatchView = () => {
  const [projects, setProjects] = useState<Project[]>([]);
  const [branches, setBranches] = useState<Branch[]>([]);
  const [promptVersions, setPromptVersions] = useState<AiPromptVersion[]>([]);
  const [jobs, setJobs] = useState<AiMrAnalysisJob[]>([]);
  const [items, setItems] = useState<AiMrAnalysisJobItem[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState('');
  const [selectedBranchName, setSelectedBranchName] = useState('');
  const [selectedPromptVersionId, setSelectedPromptVersionId] = useState('');
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null);
  const [projectSearchQuery, setProjectSearchQuery] = useState('');
  const [isProjectPopoverOpen, setIsProjectPopoverOpen] = useState(false);
  const [sinceDate, setSinceDate] = useState('');
  const [untilDate, setUntilDate] = useState('');
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [manualRunEnabled, setManualRunEnabled] = useState(false);

  const projectNameById = useMemo(() => {
    return new Map(projects.map((project) => [String(project.id), project.name]));
  }, [projects]);

  const sortedProjects = useMemo(() => {
    return [...projects].sort((a, b) => a.name.localeCompare(b.name));
  }, [projects]);

  const filteredProjects = useMemo(() => {
    if (!projectSearchQuery) return sortedProjects;
    const query = projectSearchQuery.toLowerCase();
    return sortedProjects.filter((project) =>
      project.name.toLowerCase().includes(query) ||
      project.description?.toLowerCase().includes(query)
    );
  }, [projectSearchQuery, sortedProjects]);

  const selectedProject = useMemo(() => {
    return projects.find((project) => project.id.toString() === selectedProjectId);
  }, [projects, selectedProjectId]);

  const promptLabelById = useMemo(() => {
    return new Map(
      promptVersions.map((prompt) => [
        prompt.id,
        `${prompt.name || prompt.versionKey} (#${prompt.id})`,
      ])
    );
  }, [promptVersions]);

  const loadJobs = useCallback(async () => {
    const jobList = await getMrAnalysisJobs();
    setJobs(jobList);
  }, []);

  useEffect(() => {
    const loadInitialData = async () => {
      setLoading(true);
      setError(null);
      try {
        const [projectList, promptList, jobList, settings] = await Promise.all([
          getProjects(),
          getPromptVersions(),
          getMrAnalysisJobs(),
          getMrAnalysisSettings(),
        ]);
        setProjects(projectList);
        setPromptVersions(promptList);
        setJobs(jobList);
        setManualRunEnabled(settings.manualRunEnabled);
        if (projectList.length > 0) {
          setSelectedProjectId(String(projectList[0].id));
        }
        if (promptList.length > 0) {
          setSelectedPromptVersionId(String(promptList[0].id));
        }
      } catch (loadError) {
        setError(loadError instanceof Error ? loadError.message : '初期データの取得に失敗しました');
      } finally {
        setLoading(false);
      }
    };

    void loadInitialData();
  }, []);

  useEffect(() => {
    if (!selectedProjectId) return;

    const loadProjectBranches = async () => {
      setError(null);
      try {
        const branchList = await getBranches(selectedProjectId);
        setBranches(branchList);
        const defaultBranch = latestBranch(branchList);
        setSelectedBranchName(defaultBranch?.name || '');
      } catch (branchError) {
        setBranches([]);
        setSelectedBranchName('');
        setError(branchError instanceof Error ? branchError.message : 'ブランチの取得に失敗しました');
      }
    };

    void loadProjectBranches();
  }, [selectedProjectId]);

  useEffect(() => {
    if (!selectedProjectId || !selectedBranchName) return;

    const loadActivityPeriod = async () => {
      try {
        const period = await getMergeRequestActivityPeriod(selectedProjectId, selectedBranchName);
        setSinceDate(dateOnly(period.periodStart || period.firstMergeCreatedDate));
        setUntilDate(dateOnly(period.periodEnd || period.lastMergeMergedDate));
      } catch {
        setSinceDate('');
        setUntilDate('');
      }
    };

    void loadActivityPeriod();
  }, [selectedProjectId, selectedBranchName]);

  const handleRun = async () => {
    if (!manualRunEnabled || !selectedProjectId || !selectedBranchName || !sinceDate || !untilDate) return;
    setRunning(true);
    setError(null);
    setMessage(null);
    try {
      const job = await runMrAnalysisJob({
        projectId: selectedProjectId,
        refName: selectedBranchName,
        sinceDate,
        untilDate,
        promptVersionId: selectedPromptVersionId ? Number(selectedPromptVersionId) : undefined,
      });
      setMessage(`MR AI分析ジョブ #${job.id} が完了しました`);
      setPromptVersions(await getPromptVersions());
      await loadJobs();
      setSelectedJobId(job.id);
      setItems(await getMrAnalysisJobItems(job.id));
    } catch (runError) {
      setError(runError instanceof Error ? runError.message : 'MR AI分析ジョブの実行に失敗しました');
    } finally {
      setRunning(false);
    }
  };

  const handleProjectSelect = (projectId: string) => {
    setSelectedProjectId(projectId);
    setSelectedBranchName('');
    setBranches([]);
    setSinceDate('');
    setUntilDate('');
    setProjectSearchQuery('');
    setIsProjectPopoverOpen(false);
  };

  const handleSelectJob = async (jobId: number) => {
    setSelectedJobId(jobId);
    setDetailLoading(true);
    setError(null);
    try {
      setItems(await getMrAnalysisJobItems(jobId));
    } catch (detailError) {
      setError(detailError instanceof Error ? detailError.message : 'ジョブ明細の取得に失敗しました');
    } finally {
      setDetailLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="mb-4">
            <h3 className="text-base font-bold text-slate-800">MR AI分析（逐次実行）</h3>
            <p className="mt-1 text-xs text-slate-500">
              対象MRを1件ずつGeminiで分析して保存します。Gemini Batch APIの非同期ジョブではありません。
            </p>
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <label className="space-y-1.5 text-sm font-semibold text-slate-700">
              プロジェクト
              <Popover open={isProjectPopoverOpen} onOpenChange={setIsProjectPopoverOpen}>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    className="flex w-full items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white px-3 py-2 text-left text-sm font-medium text-slate-800 outline-none transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:text-slate-400"
                    disabled={loading || running}
                  >
                    <div className="flex min-w-0 items-center gap-2">
                      <FolderKanban className="h-4 w-4 flex-shrink-0 text-blue-500" />
                      <span className="truncate">{selectedProject?.name || 'プロジェクトを選択'}</span>
                    </div>
                    <ChevronDown className={cn('h-4 w-4 flex-shrink-0 text-slate-400 transition-transform', isProjectPopoverOpen && 'rotate-180')} />
                  </button>
                </PopoverTrigger>
                <PopoverContent className="w-80 bg-white p-0" align="start">
                  <div className="border-b border-slate-100 bg-slate-50 p-2">
                    <div className="relative">
                      <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
                      <input
                        type="text"
                        placeholder="Search projects..."
                        className="w-full rounded-md border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
                        value={projectSearchQuery}
                        onChange={(event) => setProjectSearchQuery(event.target.value)}
                      />
                    </div>
                  </div>
                  <div className="max-h-80 overflow-y-auto p-1">
                    {filteredProjects.length === 0 ? (
                      <div className="py-6 text-center text-sm text-slate-500">
                        プロジェクトがありません
                      </div>
                    ) : (
                      filteredProjects.map((project) => (
                        <button
                          key={project.id}
                          type="button"
                          onClick={() => handleProjectSelect(project.id.toString())}
                          className={cn(
                            'flex w-full items-start gap-3 rounded-md px-3 py-2.5 text-left transition-colors',
                            selectedProjectId === project.id.toString() ? 'bg-blue-50' : 'hover:bg-slate-50'
                          )}
                        >
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center justify-between gap-2">
                              <span className={cn(
                                'truncate text-sm font-medium',
                                selectedProjectId === project.id.toString() ? 'text-blue-700' : 'text-slate-700'
                              )}>
                                {project.name}
                              </span>
                              {selectedProjectId === project.id.toString() && (
                                <Check className="h-4 w-4 flex-shrink-0 text-blue-600" />
                              )}
                            </div>
                            {project.description && (
                              <p className="mt-0.5 line-clamp-2 text-xs font-normal text-slate-400">
                                {project.description}
                              </p>
                            )}
                          </div>
                        </button>
                      ))
                    )}
                  </div>
                </PopoverContent>
              </Popover>
            </label>

            <label className="space-y-1.5 text-sm font-semibold text-slate-700">
              ブランチ
              <select
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-500"
                value={selectedBranchName}
                onChange={(event) => setSelectedBranchName(event.target.value)}
                disabled={!selectedProjectId || running}
              >
                {branches.map((branch) => (
                  <option key={branch.name} value={branch.name}>
                    {branch.name}
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1.5 text-sm font-semibold text-slate-700">
              開始日
              <input
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-500"
                type="date"
                value={sinceDate}
                onChange={(event) => setSinceDate(event.target.value)}
                disabled={running}
              />
            </label>

            <label className="space-y-1.5 text-sm font-semibold text-slate-700">
              終了日
              <input
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-500"
                type="date"
                value={untilDate}
                onChange={(event) => setUntilDate(event.target.value)}
                disabled={running}
              />
            </label>

            <label className="space-y-1.5 text-sm font-semibold text-slate-700 md:col-span-2">
              プロンプト版
              <select
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-500"
                value={selectedPromptVersionId}
                onChange={(event) => setSelectedPromptVersionId(event.target.value)}
                disabled={running}
              >
                <option value="">デフォルトプロンプト</option>
                {promptVersions.map((prompt) => (
                  <option key={prompt.id} value={prompt.id}>
                    {prompt.name || prompt.versionKey} / {prompt.model}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={handleRun}
              disabled={!manualRunEnabled || running || !selectedProjectId || !selectedBranchName || !sinceDate || !untilDate}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              実行
            </button>
            <button
              type="button"
              onClick={() => void loadJobs()}
              disabled={running}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            >
              <RefreshCw className="h-4 w-4" />
              履歴更新
            </button>
          </div>

          {!manualRunEnabled && !loading && (
            <p className="mt-4 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-600">
              公開デモではMR分析ジョブの実行を停止しています。保存済みの履歴は下で確認できます。AI評価の例はダッシュボードでご覧ください。
            </p>
          )}

          {message && <p className="mt-4 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-700">{message}</p>}
          {error && <p className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <h3 className="mb-3 text-base font-bold text-slate-800">ジョブ明細</h3>
          {detailLoading ? (
            <div className="flex items-center gap-2 text-sm text-slate-500">
              <Loader2 className="h-4 w-4 animate-spin" />
              読み込み中
            </div>
          ) : items.length === 0 ? (
            <p className="text-sm text-slate-500">履歴からジョブを選択すると明細を表示します。</p>
          ) : (
            <div className="max-h-72 overflow-auto rounded-lg border border-slate-100">
              <table className="w-full text-left text-xs">
                <thead className="sticky top-0 bg-slate-50 text-slate-500">
                  <tr>
                    <th className="px-3 py-2">MR</th>
                    <th className="px-3 py-2">作者</th>
                    <th className="px-3 py-2">状態</th>
                    <th className="px-3 py-2">行数</th>
                    <th className="px-3 py-2">理由</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((item) => (
                    <tr key={item.id} className="text-slate-700">
                      <td className="px-3 py-2 font-semibold">MR-id{item.mrIid}</td>
                      <td className="px-3 py-2">{item.authorUsername || '-'}</td>
                      <td className="px-3 py-2">{item.status}</td>
                      <td className="px-3 py-2">{item.diffLineCount ?? '-'}</td>
                      <td className="px-3 py-2">{item.skipReason || item.errorMessage || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-base font-bold text-slate-800">実行履歴</h3>
        <div className="max-h-80 overflow-auto rounded-lg border border-slate-100">
          <table className="w-full text-left text-xs">
            <thead className="sticky top-0 bg-slate-50 text-slate-500">
              <tr>
                <th className="px-3 py-2">ID</th>
                <th className="px-3 py-2">プロジェクト</th>
                <th className="px-3 py-2">ブランチ</th>
                <th className="px-3 py-2">期間</th>
                <th className="px-3 py-2">Prompt</th>
                <th className="px-3 py-2">Model</th>
                <th className="px-3 py-2">状態</th>
                <th className="px-3 py-2">件数</th>
                <th className="px-3 py-2">実行日時</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {jobs.map((job) => (
                <tr
                  key={job.id}
                  className={`cursor-pointer text-slate-700 hover:bg-blue-50/50 ${
                    selectedJobId === job.id ? 'bg-blue-50' : ''
                  }`}
                  onClick={() => void handleSelectJob(job.id)}
                >
                  <td className="px-3 py-2 font-semibold">#{job.id}</td>
                  <td className="px-3 py-2">{projectNameById.get(job.projectId) || job.projectId}</td>
                  <td className="px-3 py-2">{job.refName || '-'}</td>
                  <td className="px-3 py-2">
                    {job.sinceDate} - {job.untilDate}
                  </td>
                  <td className="px-3 py-2">{promptLabelById.get(job.promptVersionId) || `#${job.promptVersionId}`}</td>
                  <td className="px-3 py-2">{job.model}</td>
                  <td className="px-3 py-2">{job.status}</td>
                  <td className="px-3 py-2">
                    {job.targetCount} / {job.analyzedCount} / {job.skippedCount} / {job.failedCount}
                  </td>
                  <td className="px-3 py-2">{formatDateTime(job.requestedAt)}</td>
                </tr>
              ))}
              {jobs.length === 0 && (
                <tr>
                  <td className="px-3 py-6 text-center text-slate-500" colSpan={9}>
                    実行履歴はまだありません。
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
