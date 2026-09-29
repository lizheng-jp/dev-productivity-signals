'use client';

import { useState } from 'react';
import Image from 'next/image';
import ComparisonModal from '@/components/ComparisonModal';

const developers = [
  {
    rank: 1,
    name: 'Developer A',
    avatar: '/avatars/a.png',
    commitCount: 120,
    mrCount: 45,
    leadTime: '2.5d',
    reviewCount: 80,
    score: 98,
    grade: 'A',
  },
  {
    rank: 2,
    name: 'Developer B',
    avatar: '/avatars/b.png',
    commitCount: 110,
    mrCount: 40,
    leadTime: '3.1d',
    reviewCount: 75,
    score: 95,
    grade: 'A',
  },
  {
    rank: 3,
    name: 'Developer C',
    avatar: '/avatars/c.png',
    commitCount: 100,
    mrCount: 35,
    leadTime: '2.8d',
    reviewCount: 70,
    score: 92,
    grade: 'A',
  },
  {
    rank: 4,
    name: 'Developer D',
    avatar: '/avatars/d.png',
    commitCount: 90,
    mrCount: 30,
    leadTime: '4.2d',
    reviewCount: 65,
    score: 88,
    grade: 'B',
  },
  {
    rank: 5,
    name: 'Developer E',
    avatar: '/avatars/e.png',
    commitCount: 80,
    mrCount: 25,
    leadTime: '3.5d',
    reviewCount: 60,
    score: 85,
    grade: 'B',
  },
];

const getRankClass = (rank: number) => {
  switch (rank) {
    case 1:
      return 'animate-glow-gold [--glow-color:gold]';
    case 2:
      return 'animate-glow-silver [--glow-color:silver]';
    case 3:
      return 'animate-glow-bronze [--glow-color:#cd7f32]';
    default:
      return '';
  }
};

const getGradeClass = (grade: string) => {
  switch (grade) {
    case 'A':
      return 'bg-green-500 text-white';
    case 'B':
      return 'bg-yellow-500 text-white';
    case 'C':
      return 'bg-red-500 text-white';
    default:
      return 'bg-gray-500 text-white';
  }
};

export default function Leaderboard() {
  const [isModalOpen, setIsModalOpen] = useState(false);

  return (
    <>
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-border">
          <thead className="bg-secondary">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Developer</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Commit Count</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">MR Count</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Avg. Lead Time</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Code Review Count</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Total Score</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Grade</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-muted-foreground uppercase tracking-wider">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {developers.map((dev) => (
              <tr key={dev.rank} className={`hover:bg-secondary/80 ${getRankClass(dev.rank)}`}>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">
                  <div className="flex items-center">
                    <div className="flex-shrink-0 h-10 w-10">
                      <Image className="h-10 w-10 rounded-full" src={dev.avatar} alt="" width={40} height={40} />
                    </div>
                    <div className="ml-4">
                      <div className="text-sm font-medium text-foreground">{dev.name}</div>
                    </div>
                  </div>
                </td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">{dev.commitCount}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">{dev.mrCount}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">{dev.leadTime}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">{dev.reviewCount}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-foreground">{dev.score}</td>
                <td className="px-6 py-4 whitespace-nowrap">
                  <span className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full ${getGradeClass(dev.grade)}`}>
                    {dev.grade}
                  </span>
                </td>
                <td className="px-6 py-4 whitespace-nowrap text-sm font-medium">
                  <button onClick={() => setIsModalOpen(true)} className="px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90">
                    Compare
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {isModalOpen && <ComparisonModal onClose={() => setIsModalOpen(false)} />}
    </>
  );
}
