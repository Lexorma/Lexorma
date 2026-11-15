import { api, enc } from '../../../lib/api';
import { t } from '../../../lib/i18n';

/**
 * AI 설명 제안 (AI-5) API 와 형식. 제안은 승인 전까지 업무 설명을 바꾸지 않는다 — 승인·반려·보류는 서버가 권한·버전·근거 변경을 다시 확인한다.
 */
export type TargetType = 'TABLE' | 'COLUMN';
export type ReviewStatus = 'PROPOSED' | 'REVIEWED' | 'APPROVED' | 'REJECTED' | 'ON_HOLD' | 'REVIEW_REQUIRED' | 'SUPERSEDED' | 'CLOSED';
export type Certainty = 'VERIFIED' | 'INFERRED' | 'UNKNOWN';
export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED' | 'INTERRUPTED';

export interface ProposalSummary {
  id: number; targetType: TargetType; tableId: number; columnId: number | null; targetName: string; cycle: number;
  status: ReviewStatus; certainty: Certainty; proposedDescription: string; confirmedDescription: string | null;
  model: string | null; promptVersion: string; creationMethod: string; jobId: number | null; createdAt: string | null;
  reviewerId: string | null; reviewedAt: string | null; reviewReason: string | null; recheckNeeded: boolean; version: number;
  evidenceTotal: number; evidenceVerified: number;
  /** 생성 때 이 제안과 다른 업무 설명이 있었다 — 승인하면 교체한다 */
  replacesBizDescription: boolean;
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
export interface Change { kind: string; type: string | null; id: number | null; name: string | null; fields: string[]; diffs?: ChangeDiff[]; changedAt?: string | null }
export interface HistoryItem { id: number; action: string; before: ReviewStatus | null; after: ReviewStatus; processorId: string | null; processedAt: string; reason: string | null; confirmedDescription: string | null }
export interface ProposalDetail {
  proposal: ProposalSummary; detail: DraftDetail | null; evidence: EvidenceItem[] | null;
  current: { exists: boolean; name: string; description: string | null; sourceComment: string | null; bizDescription: string | null; approved: boolean | null };
  changes: Change[]; history: HistoryItem[];
  /** 제안을 만들 때(AI 를 부르기 전) 대상의 업무 설명 — 승인은 지금 값이 이와 같을 때만 반영된다 */
  baseBizDescription: string | null;
}
export interface ReviewResult { outcome: string; proposal: ProposalSummary; changes: Change[] }
export interface TargetRow {
  targetType: TargetType; tableId: number; columnId: number | null; name: string; description: string | null;
  hasSourceComment: boolean; hasBizDescription: boolean; proposalId: number | null; proposalStatus: ReviewStatus | null; recheckNeeded: boolean;
  detail: TargetDetail | null;
}
/** 대상 상세 : 테이블 목록에는 개수만, 테이블을 펼치면 설명들 (없으면 null) */
export interface TargetDetail {
  schemaName: string | null; physicalName: string | null; logicalName: string | null; modelDescription: string | null; sourceComment: string | null;
  bizDescription: string | null; dataType: string | null; pk: boolean | null; fk: boolean | null; termName: string | null; domainName: string | null;
  columnCount: number | null; missingColumnCount: number | null;
}
export type PreviewOutcome = 'CREATE' | 'OPEN_PROPOSAL' | 'DESCRIBED';
export interface JobPreview {
  items: { targetType: TargetType; tableId: number; columnId: number | null; name: string; outcome: PreviewOutcome }[];
  create: number; openProposal: number; described: number; targets: number; limit: number;
}
export interface JobBody { scope: string; tableId?: number | null; onlyMissing?: boolean; targets?: { tableId: number; columnId: number | null }[]; tables?: boolean; columns?: boolean }
export interface RevalidateResult { outcome: 'REVIEWABLE' | 'STILL_REQUIRED'; acknowledgeable: boolean; proposal: ProposalSummary; changes: Change[] }
export interface ObjectStatus { targetType: TargetType; tableId: number; columnId: number | null; bizDescription: string | null; proposalId: number | null; proposalStatus: ReviewStatus | null; recheckNeeded: boolean }
export interface JobSummary {
  id: number; status: JobStatus; targetCount: number; completedCount: number; failedCount: number; skippedCount: number;
  requestedAt: string | null; startedAt: string | null; endedAt: string | null; errors: { target: string | null; code: string }[];
}
export interface Page<T> { content: T[]; totalElements: number; number: number; totalPages: number }

const BASE = '/api/ai/dictionary';
/** 대상 테이블 목록 한 쪽의 크기 (서버 기본값과 같다) */
export const TARGET_PAGE_SIZE = 30;

export const aiDictionary = {
  list: (q: { status?: string; tableId?: number | null; keyword?: string; recheck?: boolean; page?: number }) =>
    api<Page<ProposalSummary>>(`${BASE}/proposals?status=${enc(q.status ?? '')}&tableId=${q.tableId ?? ''}&keyword=${enc(q.keyword ?? '')}&recheck=${q.recheck ? 'true' : ''}&page=${q.page ?? 0}&size=20`),
  detail: (id: number) => api<ProposalDetail>(`${BASE}/proposals/${id}`),
  /** 생성 대상 테이블 목록 (서버 쪽 나누기 — page 0 부터, 30개씩, 테이블 이름 순) */
  targetTables: (q: { onlyMissing: boolean; keyword: string; page: number }) =>
    api<Page<TargetRow>>(`${BASE}/targets/tables?onlyMissing=${q.onlyMissing}&keyword=${enc(q.keyword)}&page=${q.page}&size=${TARGET_PAGE_SIZE}`),
  /** tableId 가 있으면 그 테이블과 컬럼의 상세. 없으면 앞에서 최대 500 테이블 (품질 평가의 테이블 고르기) */
  targets: (tableId: number | null, onlyMissing: boolean, keyword = '') =>
    api<TargetRow[]>(`${BASE}/targets?tableId=${tableId ?? ''}&onlyMissing=${onlyMissing}&keyword=${enc(keyword)}`),
  objects: (tableId: number) => api<ObjectStatus[]>(`${BASE}/objects/${tableId}`, { silent: true }),
  generate: (tableId: number, columnId: number | null, regenerate: boolean) =>
    api<{ outcome: 'CREATED' | 'SKIPPED'; proposal: ProposalSummary }>(`${BASE}/proposals/generate`, { method: 'POST', body: { tableId, columnId, regenerate } }),
  startJob: (body: JobBody) => api<JobSummary>(`${BASE}/jobs`, { method: 'POST', body }),
  preview: (body: JobBody) => api<JobPreview>(`${BASE}/jobs/preview`, { method: 'POST', body, silent: true }),
  revalidate: (id: number, body: { version: number; acknowledge?: boolean; reason?: string | null }) =>
    api<RevalidateResult>(`${BASE}/proposals/${id}/revalidate`, { method: 'POST', body }),
  jobs: () => api<JobSummary[]>(`${BASE}/jobs`),
  job: (id: number) => api<JobSummary>(`${BASE}/jobs/${id}`, { silent: true }),
  review: (id: number, action: 'approve' | 'reject' | 'hold' | 'review' | 'close', body: { version: number; description?: string | null; reason?: string | null }) =>
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
  CLOSED: t('종결'),
};

export const STATUS_CLASS: Record<ReviewStatus, string> = {
  PROPOSED: 'gh-label-accent',
  REVIEWED: 'gh-label-accent',
  APPROVED: 'gh-label-success',
  REJECTED: 'gh-label-danger',
  ON_HOLD: 'gh-label-attention',
  REVIEW_REQUIRED: 'gh-label-attention',
  SUPERSEDED: '',
  CLOSED: '',
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
  TERM_LINK_CHANGED: t('표준 용어 연결 변경'),
  MODIFIED_UNKNOWN: t('근거가 바뀌었지만 세부 내용을 알 수 없음'),
  BIZ_DESCRIPTION_CHANGED: t('다른 사용자가 업무 설명을 수정함'),
  SOURCE_CHANGED: t('원본 DB 최신화로 원본 값이 바뀜'),
  SOURCE_TABLE_MISSING: t('원본 테이블을 찾을 수 없음'),
  // 변경이 아닌 서버 사유 (reviewReason.ts 의 종류)
  generatedChanged: t('생성 중 대상이 바뀜'),
  revalidatedClean: t('근거 재검증 — 남은 변경 없음'),
  superseded: t('새 제안으로 대체됨'),
  replaced: t('앞 제안을 대체함'),
  unknown: t('근거 다시 확인 필요'),
};

/** 근거·변경 대상의 종류 (서버 EvidenceType) */
export const OBJECT_TYPE_LABEL: Record<string, string> = {
  SCHEMA: t('스키마'), TABLE: t('테이블'), COLUMN: t('컬럼'), RELATION: t('관계'), TERM: t('용어'), WORD: t('단어'), DOMAIN: t('도메인'), HISTORY: t('변경 이력'),
};

/** 대상·제안으로 이동하는 주소 (데이터 사전 › AI 설명 제안) */
export function proposalPath(target: { proposalId?: number | null; tableId?: number | null; columnId?: number | null }) {
  if (target.proposalId) return `/aiDictionary?proposal=${target.proposalId}`;
  return `/aiDictionary?tableId=${target.tableId ?? ''}${target.columnId ? `&columnId=${target.columnId}` : ''}`;
}
