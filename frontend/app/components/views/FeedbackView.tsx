'use client';

import { useState, type FormEvent } from 'react';
import { MessageSquareText, Send } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Card } from '@/components/ui/Common';
import { submitUserFeedback } from '@/lib/api/user-feedback';

export function FeedbackView() {
    const locale = useLocale() === 'ja' ? 'ja' : 'en';
    const t = useTranslations('Feedback');
    const [category, setCategory] = useState<'improvement' | 'bug' | 'question' | 'other'>('improvement');
    const [subject, setSubject] = useState('');
    const [context, setContext] = useState('');
    const [details, setDetails] = useState('');
    const [status, setStatus] = useState<'saved' | 'error' | null>(null);
    const [saving, setSaving] = useState(false);
    const fieldClass = 'mt-2 w-full rounded-lg border border-slate-300 bg-surface px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100';

    async function saveFeedback(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setStatus(null);
        setSaving(true);
        try {
            await submitUserFeedback({
                category,
                subject: subject.trim(),
                context: context.trim() || undefined,
                details: details.trim(),
                locale,
            });
            setSubject('');
            setContext('');
            setDetails('');
            setStatus('saved');
        } catch {
            setStatus('error');
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="space-y-6">
            <header className="space-y-3">
                <MessageSquareText className="h-8 w-8 text-blue-600" aria-hidden="true" />
                <h1 className="text-3xl font-extrabold tracking-tight text-slate-900">{t('title')}</h1>
                <p className="max-w-3xl text-sm leading-7 text-slate-600">{t('description')}</p>
            </header>

            <Card className="max-w-3xl p-6">
                <form onSubmit={saveFeedback} onChange={() => setStatus(null)} className="space-y-5">
                    <label className="block text-sm font-medium" htmlFor="feedback-category">
                        {t('category')}
                        <select
                            id="feedback-category"
                            className={fieldClass}
                            value={category}
                            onChange={event => setCategory(event.target.value as typeof category)}
                        >
                            {(['improvement', 'bug', 'question', 'other'] as const).map(value => (
                                <option key={value} value={value}>{t(`categories.${value}`)}</option>
                            ))}
                        </select>
                    </label>
                    <label className="block text-sm font-medium" htmlFor="feedback-subject">
                        {t('subject')}
                        <input
                            id="feedback-subject"
                            required
                            maxLength={120}
                            pattern=".*\S.*"
                            className={fieldClass}
                            value={subject}
                            onChange={event => setSubject(event.target.value)}
                            placeholder={t('subjectPlaceholder')}
                        />
                    </label>
                    <label className="block text-sm font-medium" htmlFor="feedback-context">
                        {t('context')}
                        <input
                            id="feedback-context"
                            maxLength={300}
                            className={fieldClass}
                            value={context}
                            onChange={event => setContext(event.target.value)}
                            placeholder={t('contextPlaceholder')}
                        />
                    </label>
                    <label className="block text-sm font-medium" htmlFor="feedback-details">
                        {t('details')}
                        <textarea
                            id="feedback-details"
                            required
                            maxLength={5000}
                            rows={9}
                            className={fieldClass}
                            value={details}
                            onChange={event => setDetails(event.target.value)}
                            placeholder={t('detailsPlaceholder')}
                        />
                    </label>
                    <p className="text-xs leading-6 text-slate-500">{t('privacy')}</p>
                    <button
                        type="submit"
                        disabled={saving || !subject.trim() || !details.trim()}
                        className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                        <Send className="h-4 w-4" aria-hidden="true" />
                        {saving ? t('saving') : t('submit')}
                    </button>
                    <p role="status" className="text-sm leading-6 text-slate-700">
                        {status ? t(status) : ''}
                    </p>
                </form>
            </Card>
        </div>
    );
}
