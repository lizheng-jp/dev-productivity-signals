import { post } from './client';

export interface UserFeedbackSubmission {
  category: 'improvement' | 'bug' | 'question' | 'other';
  subject: string;
  context?: string;
  details: string;
  locale: 'ja' | 'en';
}

export interface UserFeedbackReceipt {
  id: number;
  status: string;
  createdAt: string;
}

export const submitUserFeedback = (submission: UserFeedbackSubmission) =>
  post<UserFeedbackReceipt>('/api/user-feedback', submission);
