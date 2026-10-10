"use client";

import { useEffect, useRef, useState } from 'react';
import { addDays, format } from 'date-fns';
import { ArrowUp, Bot, ExternalLink, RotateCcw, X } from 'lucide-react';
import { useLocale } from 'next-intl';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useMainLayout } from '@/contexts/MainLayoutContext';
import { post } from '@/lib/api/client';
import { type AgentModel } from '@/lib/agent-models';

type AgentSource = {
    tool: string; apiPath: string; projectUrl: string;
    sourceId?: string; sourceType?: string; entityId?: number; eventDate?: string; score?: number;
};
type AgentResponse = { requestId: string; executionId: string; answer: string; sources: AgentSource[] };
type ChatMessage = {
    id: string;
    role: 'user' | 'assistant';
    text: string;
    context?: string;
    sources?: AgentSource[];
    isError?: boolean;
};

function githubUrl(value?: string) {
    if (!value) return undefined;
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && url.hostname === 'github.com' ? url.toString() : undefined;
    } catch {
        return undefined;
    }
}

const markdownComponents: Components = {
    h1: ({ children }) => <h3 className="mb-2 mt-4 text-sm font-semibold first:mt-0">{children}</h3>,
    h2: ({ children }) => <h3 className="mb-2 mt-4 text-sm font-semibold first:mt-0">{children}</h3>,
    h3: ({ children }) => <h3 className="mb-2 mt-4 text-sm font-semibold first:mt-0">{children}</h3>,
    p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
    ul: ({ children }) => <ul className="mb-2 list-disc space-y-1 pl-5">{children}</ul>,
    ol: ({ children }) => <ol className="mb-2 list-decimal space-y-1 pl-5">{children}</ol>,
    li: ({ children }) => <li className="break-words">{children}</li>,
    a: ({ href, children }) => {
        const safeHref = githubUrl(href);
        return safeHref ? <a href={safeHref} target="_blank" rel="noopener noreferrer"
            className="inline-flex items-baseline gap-0.5 break-all text-blue-700 underline underline-offset-2">
            {children}<ExternalLink className="h-3 w-3 shrink-0 self-center" aria-hidden="true" />
        </a> : <span>{children}</span>;
    },
    table: ({ children }) => <div className="my-3 max-w-full overflow-x-auto rounded border border-slate-300">
        <table className="min-w-[32rem] border-collapse text-left text-xs leading-5">{children}</table>
    </div>,
    th: ({ children }) => <th className="border-b border-r border-slate-300 bg-slate-200 px-2 py-1.5 align-top font-semibold last:border-r-0">{children}</th>,
    td: ({ children }) => <td className="max-w-48 border-b border-r border-slate-200 px-2 py-1.5 align-top break-words last:border-r-0">{children}</td>,
    pre: ({ children }) => <pre className="my-2 max-w-full overflow-x-auto rounded bg-slate-200 p-2 text-xs">{children}</pre>,
    code: ({ children }) => <code className="rounded bg-slate-200 px-1 py-0.5 text-[0.9em] break-all">{children}</code>,
    blockquote: ({ children }) => <blockquote className="my-2 border-l-2 border-slate-400 pl-3 text-slate-600">{children}</blockquote>,
    hr: () => <hr className="my-3 border-slate-300" />,
};

function AgentSources({ sources, isJapanese }: { sources: AgentSource[]; isJapanese: boolean }) {
    const uniqueSources = sources.filter((source, index) => sources.findIndex(item =>
        item.tool === source.tool && item.sourceId === source.sourceId
        && item.apiPath === source.apiPath && item.projectUrl === source.projectUrl) === index);
    const toolLabels: Record<string, string> = isJapanese ? {
        get_project_metrics: 'プロジェクト指標',
        get_project_comparison: '期間比較',
        get_member_metrics: '開発者指標',
        get_merge_requests: 'Pull Request 一覧',
        get_merge_request_details: 'Pull Request 詳細',
        search_project_evidence: '関連する議論',
    } : {
        get_project_metrics: 'Project metrics',
        get_project_comparison: 'Period comparison',
        get_member_metrics: 'Developer metrics',
        get_merge_requests: 'Pull requests',
        get_merge_request_details: 'Pull request details',
        search_project_evidence: 'Related discussions',
    };

    return <details className="mt-3 border-t border-slate-300 pt-2 text-xs">
        <summary className="cursor-pointer font-medium text-slate-600">
            {isJapanese ? '参照データ' : 'Sources'} ({uniqueSources.length})
        </summary>
        <ul className="mt-2 space-y-1.5">
            {uniqueSources.map((source, index) => <li key={`${source.tool}-${source.sourceId || index}`} className="break-words">
                <span>{source.sourceId && source.entityId != null
                    ? `${source.sourceType === 'issue' || source.sourceType === 'issue_comment' ? 'Issue' : 'PR'} #${source.entityId}${source.eventDate ? ` · ${source.eventDate}` : ''}`
                    : toolLabels[source.tool] || source.tool}</span>
                {githubUrl(source.projectUrl) && <a href={source.projectUrl} target="_blank" rel="noopener noreferrer"
                    className="ml-2 inline-flex items-center gap-1 text-blue-700 underline underline-offset-2">
                    GitHub <ExternalLink className="h-3 w-3" aria-hidden="true" />
                </a>}
            </li>)}
        </ul>
    </details>;
}

export function AgentView({ onOpenSettings, model }: {
    onOpenSettings: () => void;
    model: AgentModel;
}) {
    const isJapanese = useLocale() === 'ja';
    const { projects, selectedProjectId, selectedBranch, date } = useMainLayout();
    const githubProjects = projects.filter(item => item.provider === 'github');
    const [projectOverride, setProjectOverride] = useState('');
    const [sinceOverride, setSinceOverride] = useState('');
    const [untilOverride, setUntilOverride] = useState('');
    const [question, setQuestion] = useState('');
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [loading, setLoading] = useState(false);
    const [isOpen, setIsOpen] = useState(false);
    const launcherRef = useRef<HTMLButtonElement>(null);
    const questionRef = useRef<HTMLTextAreaElement>(null);
    const transcriptEndRef = useRef<HTMLDivElement>(null);

    const project = githubProjects.find(item => item.id === projectOverride)
        || githubProjects.find(item => item.id === selectedProjectId)
        || githubProjects[0];
    const since = sinceOverride || format(date?.from || addDays(new Date(), -30), 'yyyy-MM-dd');
    const until = untilOverride || format(date?.to || new Date(), 'yyyy-MM-dd');
    const refName = project?.id === selectedProjectId
        ? selectedBranch || project.defaultBranch : project?.defaultBranch;
    const rangeTooLong = Date.parse(until) - Date.parse(since) > 365 * 24 * 60 * 60 * 1000;
    const canAsk = Boolean(project && question.trim() && since <= until && !rangeTooLong);

    useEffect(() => {
        if (isOpen) questionRef.current?.focus();
    }, [isOpen]);

    useEffect(() => {
        if (isOpen) transcriptEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }, [isOpen, messages, loading]);

    useEffect(() => {
        if (!isOpen) return;
        const onKeyDown = (event: KeyboardEvent) => {
            if (event.key === 'Escape') closePanel();
        };
        window.addEventListener('keydown', onKeyDown);
        return () => window.removeEventListener('keydown', onKeyDown);
    }, [isOpen]);

    function closePanel() {
        setIsOpen(false);
        launcherRef.current?.focus();
    }

    async function submit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!canAsk || !project || loading) return;

        const currentQuestion = question.trim();
        const messageId = crypto.randomUUID();
        setMessages(current => [...current, {
            id: `${messageId}-user`, role: 'user', text: currentQuestion,
            context: `${project.name} · ${since} - ${until}`,
        }]);
        setQuestion('');
        setLoading(true);
        try {
            const answer = await post<AgentResponse>('/api/agent/ask', {
                question: currentQuestion, projectId: project.id, since, until,
                refName: refName || undefined, model,
            });
            if (!answer || typeof answer.answer !== 'string' || !answer.answer.trim()
                || !Array.isArray(answer.sources)) {
                throw new Error('Invalid Agent response format');
            }
            console.info('[Agent] Answer received', { requestId: answer.requestId, executionId: answer.executionId });
            setMessages(current => [...current, {
                id: `${messageId}-assistant`, role: 'assistant', text: answer.answer, sources: answer.sources,
            }]);
        } catch (failure) {
            console.error('[Agent] Request or response failed', failure);
            const status = (failure as { status?: number }).status;
            const details = failure instanceof Error ? failure.message : '';
            setQuestion(current => current || currentQuestion);
            setMessages(current => [...current, {
                id: `${messageId}-error`, role: 'assistant', text: errorMessage(status, details, isJapanese), isError: true,
            }]);
        } finally {
            setLoading(false);
        }
    }

    return <>
        <button ref={launcherRef} type="button" onClick={() => setIsOpen(true)}
            title={isJapanese ? 'Signals Agent を開く' : 'Open Signals Agent'}
            aria-label={isJapanese ? 'Signals Agent を開く' : 'Open Signals Agent'}
            aria-expanded={isOpen} aria-controls="agent-dialog"
            className={`fixed bottom-[5.5rem] right-4 z-[60] flex h-14 w-14 items-center justify-center rounded-2xl bg-aurora text-white shadow-[0_12px_30px_-6px_rgb(139_92_246/0.6)] transition-transform hover:-translate-y-0.5 hover:scale-105 md:bottom-6 md:right-6 ${isOpen ? 'hidden' : ''}`}>
            <Bot className="h-6 w-6" aria-hidden="true" />
        </button>

        {isOpen && <section id="agent-dialog" role="dialog" aria-modal="false" aria-label="Signals Agent"
            className="fixed inset-x-3 bottom-[5.5rem] top-3 z-[60] flex flex-col overflow-hidden rounded-2xl border border-white/80 bg-white/85 shadow-2xl ring-1 ring-slate-900/5 backdrop-blur-2xl animate-rise sm:inset-x-auto sm:bottom-6 sm:right-6 sm:top-auto sm:h-[min(38rem,calc(100dvh-3rem))] sm:w-[min(28rem,calc(100vw-3rem))]">
            <header className="flex shrink-0 items-center justify-between border-b border-slate-200/70 px-4 py-3">
                <div className="flex items-center gap-2">
                    <span className="bg-aurora flex h-7 w-7 items-center justify-center rounded-lg text-white"><Bot className="h-4 w-4" aria-hidden="true" /></span>
                    <h2 className="text-sm font-semibold text-slate-900">Signals Agent</h2>
                    {loading && <span className="h-2 w-2 animate-pulse rounded-full bg-blue-600" aria-label={isJapanese ? '処理中' : 'Processing'} />}
                </div>
                <div className="flex items-center gap-1">
                    <button type="button" onClick={() => setMessages([])} disabled={messages.length === 0 || loading}
                        title={isJapanese ? '会話をクリア' : 'Clear conversation'} aria-label={isJapanese ? '会話をクリア' : 'Clear conversation'}
                        className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100 disabled:opacity-40">
                        <RotateCcw className="h-4 w-4" aria-hidden="true" />
                    </button>
                    <button type="button" onClick={closePanel} title={isJapanese ? '閉じる' : 'Close'}
                        aria-label={isJapanese ? '閉じる' : 'Close'}
                        className="flex h-8 w-8 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100">
                        <X className="h-4 w-4" aria-hidden="true" />
                    </button>
                </div>
            </header>

            <div className="shrink-0 space-y-2 border-b border-slate-200/70 bg-slate-50/60 px-4 py-3">
                {githubProjects.length > 0 ? <>
                    <label className="sr-only" htmlFor="agent-project">{isJapanese ? 'GitHub プロジェクト' : 'GitHub project'}</label>
                    <select id="agent-project" value={project?.id || ''} onChange={event => setProjectOverride(event.target.value)}
                        className="h-9 w-full rounded-lg border border-slate-200 bg-white px-2 text-sm text-slate-900">
                        {githubProjects.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
                    </select>
                    <div className="flex min-w-0 items-center gap-2">
                        <label className="sr-only" htmlFor="agent-since">{isJapanese ? '開始日' : 'Start date'}</label>
                        <input id="agent-since" type="date" value={since} max={until}
                            onChange={event => setSinceOverride(event.target.value)}
                            className="h-9 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-900" />
                        <span className="shrink-0 text-xs text-slate-500">-</span>
                        <label className="sr-only" htmlFor="agent-until">{isJapanese ? '終了日' : 'End date'}</label>
                        <input id="agent-until" type="date" value={until} min={since} max={format(new Date(), 'yyyy-MM-dd')}
                            onChange={event => setUntilOverride(event.target.value)}
                            className="h-9 min-w-0 flex-1 rounded-lg border border-slate-200 bg-white px-2 text-xs text-slate-900" />
                    </div>
                    {rangeTooLong && <p className="text-xs text-red-700" role="status">
                        {isJapanese ? '期間は365日以内にしてください。' : 'Select a period of 365 days or less.'}
                    </p>}
                </> : <div className="flex items-center justify-between gap-2 text-xs text-slate-600">
                    <span>{isJapanese ? 'GitHub プロジェクトを追加してください。' : 'Add a GitHub project to ask questions.'}</span>
                    <button type="button" onClick={() => { closePanel(); onOpenSettings(); }}
                        className="shrink-0 font-medium text-blue-700 underline underline-offset-2">
                        {isJapanese ? '設定' : 'Settings'}
                    </button>
                </div>}
            </div>

            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4" aria-live="polite">
                {messages.length === 0 && <div className="flex min-h-full items-center justify-center text-center text-sm text-slate-500">
                    {isJapanese ? 'プロジェクトについて質問してください' : 'Ask about this project'}
                </div>}
                {messages.map(message => <article key={message.id} className={`flex gap-2 ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                    {message.role === 'assistant' && <Bot className="mt-2 h-4 w-4 shrink-0 text-blue-600" aria-hidden="true" />}
                    <div className={`min-w-0 rounded-2xl px-3.5 py-2.5 ${message.role === 'user' ? 'max-w-[88%]' : 'w-full'} ${message.role === 'user'
                        ? 'bg-aurora text-white shadow-md'
                        : message.isError ? 'border border-red-200 bg-red-50 text-red-800' : 'bg-white text-slate-800 shadow-sm ring-1 ring-slate-900/5'}`}>
                        {message.context && <p className="mb-1 break-words text-[10px] opacity-75">{message.context}</p>}
                        {message.role === 'assistant' && !message.isError
                            ? <div className="min-w-0 break-words text-sm leading-6">
                                <Markdown remarkPlugins={[remarkGfm]} skipHtml disallowedElements={['img']}
                                    components={markdownComponents}>{message.text}</Markdown>
                            </div>
                            : <p className="whitespace-pre-wrap break-words text-sm leading-6">{message.text}</p>}
                        {message.sources && message.sources.length > 0
                            && <AgentSources sources={message.sources} isJapanese={isJapanese} />}
                    </div>
                </article>)}
                {loading && <p className="text-sm text-slate-500" role="status">{isJapanese ? '分析中…' : 'Analyzing…'}</p>}
                <div ref={transcriptEndRef} />
            </div>

            <form onSubmit={submit} className="shrink-0 border-t border-slate-200/70 p-3">
                <label className="sr-only" htmlFor="agent-question">{isJapanese ? '質問' : 'Question'}</label>
                <div className="flex items-end gap-2">
                    <textarea ref={questionRef} id="agent-question" value={question} maxLength={1000} rows={2}
                        onChange={event => setQuestion(event.target.value)}
                        onKeyDown={event => {
                            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                                event.preventDefault();
                                event.currentTarget.form?.requestSubmit();
                            }
                        }}
                        placeholder={isJapanese ? '質問を入力' : 'Ask a question'}
                        className="max-h-32 min-h-11 min-w-0 flex-1 resize-y rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500 focus:ring-4 focus:ring-blue-500/10" />
                    <button type="submit" disabled={!canAsk || loading} title={isJapanese ? '送信' : 'Send'}
                        aria-label={isJapanese ? '送信' : 'Send'}
                        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50">
                        <ArrowUp className="h-5 w-5" aria-hidden="true" />
                    </button>
                </div>
            </form>
        </section>}
    </>;
}

function errorMessage(status: number | undefined, details: string, isJapanese: boolean) {
    if (status === 429) {
        if (details.includes('Hourly question limit')) {
            return isJapanese ? '1 時間あたりの質問数の上限に達しました。しばらくしてから再試行してください。'
                : 'You have reached the hourly question limit. Please try again later.';
        }
        if (details.includes('Daily question limit')) {
            return isJapanese ? '本日の公開デモの質問数が上限に達しました。明日（UTC）以降に再試行してください。'
                : "Today's question limit for the public demo has been reached. Please try again tomorrow (UTC).";
        }
        if (details.includes('Agent is busy')) {
            return isJapanese ? '別の質問を処理中です。完了してから再試行してください。'
                : 'Another question is already running. Please retry when it finishes.';
        }
        return isJapanese ? 'GitHub API の利用上限に達しました。リセット後に再試行してください。'
            : 'The GitHub API rate limit was reached. Please retry after it resets.';
    }
    if (status === 503) {
        return isJapanese ? 'Gemini サービスが一時的に利用できません。少し待ってから再試行してください。'
            : 'Gemini is temporarily unavailable. Please wait and try again.';
    }
    if (status === 408) {
        return isJapanese ? '応答に時間がかかりすぎました。期間を短くして再試行してください。'
            : 'The request timed out. Try a shorter date range.';
    }
    if (details === 'Invalid Agent response format') {
        return isJapanese ? 'Agent の回答形式を読み取れませんでした。再試行してください。'
            : 'The Agent response format was invalid. Please try again.';
    }
    if (status === 502) {
        return isJapanese ? 'Agent の回答を取得できませんでした。しばらくしてから再試行してください。'
            : 'Could not retrieve the Agent answer. Please try again shortly.';
    }
    return isJapanese ? '回答を生成できませんでした。再試行してください。'
        : 'Could not generate an answer. Please try again.';
}
