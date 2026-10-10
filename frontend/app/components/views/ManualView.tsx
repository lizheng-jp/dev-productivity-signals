'use client';

import { BookOpen, Brain, Database, HeartHandshake, Scale, TriangleAlert } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Card } from '@/components/ui/Common';
import { spaceGroupsConfig } from '@/lib/space-config';

const content = {
  ja: {
    title: '利用ガイド',
    intro: 'このデモは、開発活動と満足度データをSPACEフレームワークで整理し、複数の観点から傾向を確認するためのものです。',
    stepsTitle: '基本的な使い方',
    steps: [
      'ダッシュボードでプロジェクト、ブランチ、期間を選択します。',
      '総合スコアだけでなく、各SPACE次元と元の指標を確認します。',
      '比較画面で対象や期間をそろえ、変化の背景を確認します。',
      'プロジェクト分析で、GitHubプロジェクトごとのSPACE指標を並べて比較します。カードを開くと詳細を確認できます。',
      'AI評価は参考情報として、実際のMRやチーム状況と合わせて判断します。',
    ],
    sourceTitle: 'データについて',
    source: '公開デモでは、匿名化されたmockデータに加えて、設定画面からGitHubリポジトリを接続できます。GitHub APIへの接続にはサーバー側で管理するTokenを利用します。満足度調査とフィードバックはデモ用PostgreSQLに保存されます。',
    scoringTitle: 'スコアの読み方',
    scoring: '各指標を0～100点に換算し、設定した重みで次元スコアと総合スコアを計算します。欠損値は0点として扱わず、利用可能な指標の重みを再配分します。',
    satisfactionTitle: '満足度の算出',
    satisfaction: '満足度はSPACEの中で本人の感じ方を表す次元で、満足度調査の回答から算出します。調査回答がないGitHubプロジェクトでは、SPACEで調査不要の満足度指標として挙げられているコントリビューター定着率を使います。前の同じ長さの期間に活動した人のうち、当期間も活動した人の割合です（活動＝コミットまたはマージされたPR、botは除外）。前期間の活動者が3人未満の場合は未測定とします。定着率は一度きりの貢献者や異動にも左右されるため、カードに表示される人数と合わせて参考にしてください。',
    aiTitle: 'AI評価について',
    ai: 'AIモードでは、デモ用MRまたはGitHub PRの分析結果とSPACE指標をまとめて、強み・課題・改善提案を生成します。生成AIの評価には設定済みのGemini APIを利用します。',
    cautionTitle: '評価時の注意',
    caution: 'コミット数や変更行数だけでは成果や品質を判断できません。役割、期間、作業難易度、休暇などの背景と合わせて利用してください。AIの出力にも誤りが含まれる可能性があります。',
    dimensionsTitle: 'SPACE指標',
  },
  en: {
    title: 'User guide',
    intro: 'This demo organizes development activity and satisfaction data with the SPACE framework so trends can be reviewed from multiple perspectives.',
    stepsTitle: 'Basic workflow',
    steps: [
      'Select a project, branch, and date range on the dashboard.',
      'Review the source metrics and each SPACE dimension, not only the overall score.',
      'Use Comparison with aligned subjects and periods to investigate changes.',
      'Use Project Analytics to compare SPACE signals across GitHub projects, and open a card for details.',
      'Treat AI evaluation as supporting information and verify it against actual MRs and team context.',
    ],
    sourceTitle: 'Data sources',
    source: 'In addition to anonymized mock data, the public demo can connect a GitHub repository from Settings. GitHub API requests use a server-managed token. Satisfaction responses and product feedback are stored in the demo PostgreSQL database.',
    scoringTitle: 'Reading scores',
    scoring: 'Metrics are converted to a 0–100 scale and combined with configured weights. Missing values are excluded instead of being treated as zero, and the remaining weights are redistributed.',
    satisfactionTitle: 'How satisfaction is measured',
    satisfaction: 'Satisfaction is the SPACE dimension for how people feel about their work, and it comes from survey responses. For GitHub projects without responses, it uses contributor retention, the satisfaction metric SPACE lists that needs no survey: the share of people active in the previous period of equal length who are still active (active means a commit or a merged pull request; bots are excluded). With fewer than three previously active contributors it is left unmeasured. Retention is also driven by one-time contributors and role changes, so read it together with the contributor counts shown on each card.',
    aiTitle: 'AI evaluation',
    ai: 'AI mode combines analysis of demo MRs or GitHub pull requests with SPACE metrics to generate strengths, concerns, and improvement suggestions through the configured Gemini API.',
    cautionTitle: 'Interpretation',
    caution: 'Commit or changed-line counts alone do not establish outcome or quality. Consider role, period, difficulty, leave, and other context. AI output can also be inaccurate.',
    dimensionsTitle: 'SPACE metrics',
  },
} as const;

export function ManualView() {
  const locale = useLocale() === 'ja' ? 'ja' : 'en';
  const copy = content[locale];
  const tSpace = useTranslations('Space');
  const tTable = useTranslations('Table');
  const tComparison = useTranslations('Comparison');

  const overview = [
    { icon: Database, title: copy.sourceTitle, text: copy.source },
    { icon: Scale, title: copy.scoringTitle, text: copy.scoring },
    { icon: HeartHandshake, title: copy.satisfactionTitle, text: copy.satisfaction },
    { icon: Brain, title: copy.aiTitle, text: copy.ai },
    { icon: TriangleAlert, title: copy.cautionTitle, text: copy.caution },
  ];

  return (
    <div className="space-y-8">
      <header className="border-b border-slate-200 pb-6">
        <div className="flex items-center gap-3 text-blue-700">
          <BookOpen className="h-6 w-6" />
          <h1 className="text-3xl font-extrabold tracking-tight text-slate-900">{copy.title}</h1>
        </div>
        <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-600">{copy.intro}</p>
      </header>

      <section>
        <h2 className="text-base font-bold text-slate-800">{copy.stepsTitle}</h2>
        <ol className="mt-4 grid gap-3 md:grid-cols-2">
          {copy.steps.map((step, index) => (
            <li key={step} className="flex gap-3 border-l-2 border-blue-500 bg-surface px-4 py-3 text-sm leading-6 text-slate-600">
              <span className="font-bold text-blue-700">{index + 1}</span>
              <span>{step}</span>
            </li>
          ))}
        </ol>
      </section>

      <section className="grid gap-4 md:grid-cols-2">
        {overview.map(({ icon: Icon, title, text }) => (
          <Card key={title} className="p-5">
            <div className="flex items-center gap-2">
              <Icon className="h-5 w-5 text-blue-600" />
              <h2 className="text-sm font-bold text-slate-800">{title}</h2>
            </div>
            <p className="mt-3 text-sm leading-6 text-slate-600">{text}</p>
          </Card>
        ))}
      </section>

      <section>
        <h2 className="text-base font-bold text-slate-800">{copy.dimensionsTitle}</h2>
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          {spaceGroupsConfig.map(group => (
            <Card key={group.nameKey} className="p-5">
              <h3 className="text-sm font-bold text-slate-800">{tSpace(group.nameKey)}</h3>
              <p className="mt-2 text-xs leading-5 text-slate-500">{tSpace(group.descriptionKey)}</p>
              <dl className="mt-4 divide-y divide-slate-100">
                {group.metrics.map(metric => (
                  <div key={metric.key} className="py-3">
                    <dt className="text-xs font-semibold text-slate-700">{tTable(metric.labelKey)}</dt>
                    <dd className="mt-1 text-xs leading-5 text-slate-500">{tComparison(`metricDescriptions.${metric.key}`)}</dd>
                  </div>
                ))}
              </dl>
            </Card>
          ))}
        </div>
      </section>
    </div>
  );
}
