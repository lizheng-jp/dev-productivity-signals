import { post } from '@/lib/api/client';

export type AgentSource = {
    tool: string; apiPath: string; projectUrl: string;
    sourceId?: string; sourceType?: string; entityId?: number; eventDate?: string; score?: number; cited?: boolean;
};
export type AgentResponse = { requestId: string; executionId: string; answer: string; sources: AgentSource[] };
export type AgentStepStatus = 'running' | 'done' | 'failed' | 'skipped';
export type AgentStreamEvent =
    | { type: 'step'; status: AgentStepStatus; tool: string; detail?: string | null; count?: number }
    | { type: 'thought'; text: string }
    | { type: 'answer'; text: string }
    | { type: 'answer_reset' }
    | { type: 'done'; response: AgentResponse }
    | { type: 'error'; status: number; detail: string };

export class AgentStreamError extends Error {
    constructor(message: string, public status?: number) {
        super(message);
    }
}

type AskBody = { question: string; projectId: string; since: string; until: string; refName?: string; model: string };

/**
 * Asks the Agent over server-sent events and calls onEvent for each step, thought and answer piece.
 * Resolves with the final answer. Falls back to the JSON endpoint when the server has no stream endpoint.
 */
export async function askAgentStream(body: AskBody, onEvent: (event: AgentStreamEvent) => void,
    signal?: AbortSignal): Promise<AgentResponse> {
    const response = await fetch('/api/agent/ask/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        cache: 'no-store',
        signal,
    });
    if (response.status === 404) {
        return post<AgentResponse>('/api/agent/ask', body);
    }
    if (!response.ok || !response.body) {
        const text = await response.text().catch(() => '');
        let detail = text.slice(0, 500);
        try {
            detail = (JSON.parse(text) as { detail?: string }).detail || detail;
        } catch {
            // Not JSON; keep the text.
        }
        throw new AgentStreamError(detail, response.status);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = done ? '' : blocks.pop() ?? '';
        for (const block of blocks) {
            const data = block.split(/\r?\n/).filter(line => line.startsWith('data:'))
                .map(line => line.slice(5).trimStart()).join('\n');
            if (!data) continue;
            const event = JSON.parse(data) as AgentStreamEvent;
            if (event.type === 'error') throw new AgentStreamError(event.detail, event.status);
            if (event.type === 'done') return event.response;
            onEvent(event);
        }
        if (done) break;
    }
    throw new AgentStreamError('Agent stream ended without an answer', 502);
}
