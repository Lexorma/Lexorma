import { api, enc } from '../../../lib/api';
import { t } from '../../../lib/i18n';

/**
 * AI 설명 제안 (AI-5) API 와 형식. 제안은 승인 전까지 업무 설명을 바꾸지 않는다 — 승인·반려·보류는 서버가 권한·버전·근거 변경을 다시 확인한다.
 */
export type TargetType = 'TABLE' | 'COLUMN';
export type ReviewStatus = 'PROPOSED' | 'REVIEWED' | 'APPROVED' | 'REJECTED' | 'ON_HOLD' | 'REVIEW_REQUIRED' | 'SUPERSEDED';
export type Certainty = 'VERIFIED' | 'INFERRED' | 'UNKNOWN';
export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED' | 'INTERRUPTED';

export interface ProposalSummary {
  id: number; targetType: TargetType; tableId: number; columnId: number | null; targetName: string; cycle: number;
  status: ReviewStatus; certainty: Certainty; proposedDescription: string; confirmedDescription: string | null;
  model: string | null; promptVersion: string; creationMethod: string; jobId: number | null; createdAt: string | null;
  reviewerId: string | null; reviewedAt: string | null; reviewReason: string | null; recheckNeeded: boolean; version: number;
  evidenceTotal: number; evidenceVerified: number;
}

export interface EvidenceItem { type: string; id: number; name: string | null; status: string; reason: string | null; source: string | null }
export interface Recommendation { id: number; name: string; reason: string; status: string; statusReason: string | null }
export interface DraftDetail {
  nameInterpretation?: string | null;
  termRecommendation?: Recommendation; domainRecommendation?: Recommendation;
  related?: { type: string; id: number; name: string; note: string; status: string }[];
  unknowns?: string[]; questions?: string[];
  claimedCertainty?: Certainty; certainty?: Certainty; retries?: number;
  usage?: { modelCalls: number; promptTokens: number | null; completionTokens: number | null } | null;
}
export interface ChangeDiff { field: string; before: string | null; after: string | null }
export interface Change { kind: string; type: string | null; id: number | null; name: string | null; fields: string[]; diffs?: ChangeDiff[] }
export interface HistoryItem { id: number; action: string; before: ReviewStatus | null; after: ReviewStatus; processorId: string | null; processedAt: string; reason: string | null; confirmedDescription: string | null }
export interface ProposalDetail {
  proposal: ProposalSummary; detail: DraftDetail | null; evidence: EvidenceItem[] | null;
  current: { exists: boolean; name: string; description: string | null; sourceComment: string | null; bizDescription: string | null; approved: boolean | null };
  changes: Change[]; history: HistoryItem[];
}
export interface ReviewResult { outcome: string; proposal: ProposalSummary; changes: Change[] }
export interface TargetRow {
  targetType: TargetType; tableId: number; columnId: number | null; name: string; description: string | null;
  hasSourceComment: boolean; hasBizDescription: boolean; proposalId: number | null; proposalStatus: ReviewStatus | null; recheckNeeded: boolean;
}
export interface ObjectStatus { targetType: TargetType; tableId: number; columnId: number | null; bizDescription: string | null; proposalId: number | null; proposalStatus: ReviewStatus | null; recheckNeeded: boolean }
export interface JobSummary {
  id: number; status: JobStatus; targetCount: number; completedCount: number; failedCount: number; skippedCount: number;
  requestedAt: string | null; startedAt: string | null; endedAt: string | null; errors: { target: string | null; code: string }[];
}
export interface Page<T> { content: T[]; totalElements: number; number: number; totalPages: number }

const BASE = '/api/ai/dictionary';

export const aiDictionary = {
  list: (q: { status?: string; tableId?: number | null; keyword?: string; recheck?: boolean; page?: number }) =>
    api<Page<ProposalSummary>>(`${BASE}/proposals?status=${enc(q.status ?? '')}&tableId=${q.tableId ?? ''}&keyword=${enc(q.keyword ?? '')}&recheck=${q.recheck ? 'true' : ''}&page=${q.page ?? 0}&size=20`),
  detail: (id: number) => api<ProposalDetail>(`${BASE}/proposals/${id}`),
  targets: (tableId: number | null, onlyMissing: boolean, keyword = '') =>
    api<TargetRow[]>(`${BASE}/targets?tableId=${tableId ?? ''}&onlyMissing=${onlyMissing}&keyword=${enc(keyword)}`),
  objects: (tableId: number) => api<ObjectStatus[]>(`${BASE}/objects/${tableId}`, { silent: true }),
  generate: (tableId: number, columnId: number | null, regenerate: boolean) =>
    api<{ outcome: 'CREATED' | 'SKIPPED'; proposal: ProposalSummary }>(`${BASE}/proposals/generate`, { method: 'POST', body: { tableId, columnId, regenerate } }),
  startJob: (body: { scope: string; tableId?: number | null; onlyMissing?: boolean; targets?: { tableId: number; columnId: number | null }[] }) =>
    api<JobSummary>(`${BASE}/jobs`, { method: 'POST', body }),
  jobs: () => api<JobSummary[]>(`${BASE}/jobs`),
  job: (id: number) => api<JobSummary>(`${BASE}/jobs/${id}`, { silent: true }),
  review: (id: number, action: 'approve' | 'reject' | 'hold' | 'review', body: { version: number; description?: string | null; reason?: string | null }) =>
    api<ReviewResult>(`${BASE}/proposals/${id}/${action}`, { method: 'POST', body }),
  bulk: (action: 'APPROVE' | 'REJECT' | 'HOLD', items: { id: number; version: number }[], reason: string | null) =>
    api<{ id: number; outcome: string; errorCode: string | null }[]>(`${BASE}/proposals/bulk`, { method: 'POST', body: { action, items, reason } }),
  recheck: () => api<{ checked: number; reviewRequired: number; recheckFlagged: number }>(`${BASE}/proposals/recheck`, { method: 'POST', body: {} }),
};

export const STATUS_LABEL: Record<ReviewStatus, string> = {
  PROPOSED: t('검토 대기'),
  REVIEWED: t('검토 중'),
  APPROVED: t('승인'),
  REJECTED: t('반려'),
  ON_HOLD: t('보류'),
  REVIEW_REQUIRED: t('재검토 필요'),
  SUPERSEDED: t('대체됨'),
};

export const STATUS_CLASS: Record<ReviewStatus, string> = {
  PROPOSED: 'gh-label-accent',
  REVIEWED: 'gh-label-accent',
  APPROVED: 'gh-label-success',
  REJECTED: 'gh-label-danger',
  ON_HOLD: 'gh-label-attention',
  REVIEW_REQUIRED: 'gh-label-attention',
  SUPERSEDED: '',
};

export const CERTAINTY_LABEL: Record<Certainty, string> = {
  VERIFIED: t('근거로 확인'),
  INFERRED: t('추론'),
  UNKNOWN: t('알 수 없음'),
};

export const JOB_LABEL: Record<JobStatus, string> = {
  PENDING: t('대기'),
  RUNNING: t('진행 중'),
  COMPLETED: t('완료'),
  PARTIAL_FAILED: t('일부 실패'),
  FAILED: t('실패'),
  INTERRUPTED: t('중단됨'),
};

export const CHANGE_LABEL: Record<string, string> = {
  TARGET_DELETED: t('대상이 삭제됨'),
  OBJECT_DELETED: t('근거 객체가 삭제됨'),
  MODIFIED: t('근거 메타데이터가 바뀜'),
  MODIFIED_UNKNOWN: t('근거가 바뀌었지만 세부 내용을 알 수 없음'),
  BIZ_DESCRIPTION_CHANGED: t('다른 사용자가 업무 설명을 수정함'),
  SOURCE_CHANGED: t('원본 DB 최신화로 원본 값이 바뀜'),
  SOURCE_TABLE_MISSING: t('원본 테이블을 찾을 수 없음'),
};

/** 대상·제안으로 이동하는 주소 (데이터 사전 › AI 설명 제안) */
export function proposalPath(target: { proposalId?: number | null; tableId?: number | null; columnId?: number | null }) {
  if (target.proposalId) return `/aiDictionary?proposal=${target.proposalId}`;
  return `/aiDictionary?tableId=${target.tableId ?? ''}${target.columnId ? `&columnId=${target.columnId}` : ''}`;
}
