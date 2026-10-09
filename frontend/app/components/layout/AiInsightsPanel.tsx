// app/components/layout/AiInsightsPanel.tsx
import React, { useState } from 'react';
import { Sparkles, Zap, User, Users, MessageSquare, Clock, Smile, X, ArrowRight } from 'lucide-react';
import { Card, Badge, cn } from '../ui/Common';

// --- Mock AI Data ---
const AI_SUGGESTIONS = {
  individual: [
    {
      id: 1,
      dimension: 'Efficiency',
      icon: Clock,
      color: 'text-amber-600',
      bg: 'bg-amber-100',
      title: 'Context Switching Alert',
      desc: 'You have handled 5 different projects in the last 3 days. This negatively impacts deep work.',
      action: 'Block Focus Time'
    },
    {
      id: 2,
      dimension: 'Communication',
      icon: MessageSquare,
      color: 'text-blue-600',
      bg: 'bg-blue-100',
      title: 'Review Bottleneck',
      desc: 'You have 4 MRs waiting for your review > 24h. Clearing these will boost team velocity.',
      action: 'Go to Reviews'
    },
    {
      id: 3,
      dimension: 'Satisfaction',
      icon: Smile,
      color: 'text-emerald-600',
      bg: 'bg-emerald-100',
      title: 'Great Work-Life Balance',
      desc: 'Your commit patterns show healthy working hours this week. Keep it up!',
      action: null
    }
  ],
  team: [
    {
      id: 4,
      dimension: 'Performance',
      icon: Zap,
      color: 'text-purple-600',
      bg: 'bg-purple-100',
      title: 'Deployment Frequency',
      desc: 'Team "Oven Dev" deployment frequency dropped by 15% compared to last sprint.',
      action: 'Analyze Pipeline'
    },
    {
      id: 5,
      dimension: 'Activity',
      icon: Users,
      color: 'text-blue-600',
      bg: 'bg-blue-100',
      title: 'Knowledge Silo Detected',
      desc: '90% of commits in "Payment Module" are made by only 1 developer.',
      action: 'Suggest Pair Programming'
    }
  ]
};

export const AiInsightsPanel = ({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) => {
  const [activeTab, setActiveTab] = useState<'individual' | 'team'>('individual');

  if (!isOpen) return null;

  const suggestions = activeTab === 'individual' ? AI_SUGGESTIONS.individual : AI_SUGGESTIONS.team;

  return (
    <div className="w-80 bg-surface border-r border-slate-200 flex flex-col h-full shadow-xl z-20 transition-all duration-300">
      {/* Header */}
      <div className="h-16 flex items-center justify-between px-4 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-white">
        <div className="flex items-center gap-2 text-indigo-700">
          <Sparkles className="w-5 h-5 fill-indigo-200" />
          <span className="font-bold text-sm tracking-wide">AI Copilot</span>
        </div>
        <button onClick={onClose} className="p-1 hover:bg-slate-100 rounded-full text-slate-400 hover:text-slate-600">
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Tabs */}
      <div className="flex p-2 gap-2 border-b border-slate-100">
        <button
          onClick={() => setActiveTab('individual')}
          className={cn(
            "flex-1 flex items-center justify-center gap-2 py-2 text-xs font-semibold rounded-md transition-colors",
            activeTab === 'individual' ? "bg-indigo-50 text-indigo-700" : "text-slate-500 hover:bg-slate-50"
          )}
        >
          <User className="w-3.5 h-3.5" /> My Insights
        </button>
        <button
          onClick={() => setActiveTab('team')}
          className={cn(
            "flex-1 flex items-center justify-center gap-2 py-2 text-xs font-semibold rounded-md transition-colors",
            activeTab === 'team' ? "bg-indigo-50 text-indigo-700" : "text-slate-500 hover:bg-slate-50"
          )}
        >
          <Users className="w-3.5 h-3.5" /> Team Insights
        </button>
      </div>

      {/* Content Scroll */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4 bg-slate-50/50">

        {/* Summary Badge */}
        <div className="bg-indigo-600 text-white p-4 rounded-lg shadow-sm">
          <h4 className="font-bold text-sm mb-1">Weekly Summary</h4>
          <p className="text-xs text-white/85 leading-relaxed">
            {activeTab === 'individual'
              ? "Your coding efficiency is top 10% this week, but code review participation is slightly below average."
              : "Team velocity is stable, but communication overhead has increased by 12% due to long MR threads."
            }
          </p>
        </div>

        <h5 className="text-xs font-bold text-slate-400 uppercase tracking-wider mt-4">Actionable Suggestions</h5>

        {suggestions.map((item) => (
          <div key={item.id} className="bg-surface border border-slate-200 rounded-lg p-3 shadow-sm hover:shadow-md transition-shadow cursor-default group">
            <div className="flex items-start gap-3">
              <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5", item.bg, item.color)}>
                <item.icon className="w-4 h-4" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex justify-between items-center mb-1">
                  <span className="text-xs font-bold text-slate-700 truncate">{item.title}</span>
                  <Badge color="gray">{item.dimension}</Badge>
                </div>
                <p className="text-xs text-slate-500 leading-snug mb-2">
                  {item.desc}
                </p>
                {item.action && (
                  <button className="text-xs font-semibold text-indigo-600 flex items-center gap-1 hover:underline group-hover:gap-2 transition-all">
                    {item.action} <ArrowRight className="w-3 h-3" />
                  </button>
                )}
              </div>
            </div>
          </div>
        ))}

        <div className="text-center pt-4 pb-8">
            <p className="text-xs text-slate-400">AI analysis based on last 7 days activity</p>
        </div>
      </div>
    </div>
  );
};