import { api } from '../../../lib/api';
import { t, tc } from '../../../lib/i18n';
import type { ApprovalHold, Certainty, DraftDetail, EvidenceItem, ReviewStatus, TargetType } from './aiDictionary';

/**
 * AI 설명 품질 평가 (AI-5 후속) API 와 형식. 기준 데이터(평가 대상·실제 의미)와 판정은 사람이 적는다 — AI 가 판정하지 않는다.
 */
export type Category = 'CLEAR' | 'NAME_ONLY' | 'STATUS_CODE' | 'AMOUNT' | 'DATE_TIME' | 'STANDARD_LINKED' | 'RELATION' | 'NO_SOURCE_COMMENT' | 'COMMENT_DIFFERS';
export type MeaningStatus = 'PENDING' | 'CONFIRMED' | 'UNCONFIRMABLE';
export type Judgment = 'CORRECT' | 'PARTIAL' | 'INCORRECT' | 'NOT_EVALUABLE';
export type UncertaintyJudgment = 'APPROPRIATE' | 'MISSED' | 'EXCESSIVE' | 'NOT_APPLICABLE';
export type RecommendationJudgment = 'APPROPRIATE' | 'INAPPROPRIATE' | 'NO_RECOMMENDATION' | 'NOT_EVALUABLE';

export interface Candidate { targetType: TargetType; tableId: number; columnId: number | null; name: string; categories: Category[]; registered: boolean; proposalCount: number }
export interface EvaluationTarget {
  id: number; targetType: TargetType; tableId: number; columnId: number | null; targetName: string; exists: boolean; categories: Category[];
  meaningStatus: MeaningStatus; hasActualMeaning: boolean; proposalCount: number; judgmentCount: number; version: number;
}
export interface EvaluationProposal {
  id: number; model: string | null; promptVersion: string; createdAt: string | null; status: ReviewStatus; edited: boolean; certainty: Certainty;
  proposedDescription: string; confirmedDescription: string | null; detail: DraftDetail | null; evidence: EvidenceItem[] | null; sameMetadata: boolean; sameContext: boolean; invalidReferences: number;
  /** 열린 제안을 아직 승인할 수 없는 사유 (평가 미완료) */
  approvalHold: ApprovalHold | null;
}
/** 대상의 지금 메타데이터. comment : 테이블 코멘트(테이블) · 컬럼 설명(컬럼). AI 승인으로 들어간 업무 설명은 블라인드에 값을 보내지 않는다 */
export interface TargetMetadata {
  exists: boolean; tableName: string | null; columnName: string | null; comment: string | null; description: string | null; dataType: string | null; standardTerm: string | null;
  sourceComment: string | null; bizDescription: string | null; bizDescriptionFromAi: boolean; bizDescriptionHidden: boolean;
}
export interface JudgmentRow {
  id: number; proposalId: number; model: string | null; promptVersion: string; judgment: Judgment; unfoundedClaim: boolean; missingInformation: string | null;
  uncertainty: UncertaintyJudgment; termRecommendation: RecommendationJudgment; domainRecommendation: RecommendationJudgment; requiredEdits: string | null;
  reviewMinutes: number | null; writingMinutes: number | null; reviewerId: string; reviewedAt: string; version: number;
}
/** blind : 실제 의미 확인 전이라 서버가 AI 제안·판정 내용을 보내지 않았다 */
export interface TargetDetail {
  target: EvaluationTarget; actualMeaning: string | null; meaningReviewerId: string | null; meaningReviewedAt: string | null; proposals: EvaluationProposal[]; judgments: JudgmentRow[];
  blind: boolean; metadata: TargetMetadata;
}
export interface JudgmentRequest {
  targetId: number; proposalId: number; version: number | null; targetVersion: number; judgment: Judgment; unfoundedClaim: boolean; missingInformation: string | null; uncertainty: UncertaintyJudgment;
  termRecommendation: RecommendationJudgment; domainRecommendation: RecommendationJudgment; requiredEdits: string | null; reviewMinutes: number | null; writingMinutes: number | null;
  /** 저장한 판정을 고칠 때의 사유 (DEC-27 — 값이 바뀌면 필수) */
  changeReason?: string | null;
}
/** 판정 변경 이력 한 건 (DEC-27, 추가만) */
export interface JudgmentHistoryRow {
  id: number; changeType: 'INSERT' | 'UPDATE'; reviewerId: string; before: Record<string, unknown> | null; after: Record<string, unknown>; reason: string | null; changedAt: string;
}
export interface Rate { value: number | null; count: number; base: number }
export interface ModelResult {
  model: string | null; promptVersion: string; judgments: number; proposals: number; reviewers: number; accuracy: Rate; partial: Rate; notEvaluable: number; unfounded: Rate;
  fabricated: Rate; termFit: Rate; domainFit: Rate; uncertainty: Rate; missedUnknown: Rate; excessiveUnknown: Rate; editRate: Rate; approvalRate: Rate;
  medianReviewMinutes: number | null; medianWritingMinutes: number | null; timeBase: number; timeSaving: number | null; sameMetadata: number;
}
export interface Metrics { dataset: { targets: number; confirmed: number; unconfirmable: number; pending: number; categories: Record<Category, number> }; models: ModelResult[]; evaluated: boolean; note: string }

const BASE = '/api/ai/dictionary/evaluation';

export const aiEvaluation = {
  candidates: (tableId: number | null) => api<Candidate[]>(`${BASE}/candidates?tableId=${tableId ?? ''}`),
  targets: () => api<EvaluationTarget[]>(`${BASE}/targets`),
  register: (items: { tableId: number; columnId: number | null; categories: Category[] }[]) =>
    api<{ created: number; skipped: number }>(`${BASE}/targets`, { method: 'POST', body: { items } }),
  detail: (id: number) => api<TargetDetail>(`${BASE}/targets/${id}`),
  update: (id: number, body: { version: number; categories: Category[]; actualMeaning: string; status: MeaningStatus }) =>
    api<TargetDetail>(`${BASE}/targets/${id}`, { method: 'PUT', body }),
  remove: (id: number) => api<{ result: boolean }>(`${BASE}/targets/${id}`, { method: 'DELETE' }),
  judge: (body: JudgmentRequest) => api<JudgmentRow>(`${BASE}/judgments`, { method: 'POST', body }),
  judgmentHistory: (id: number) => api<JudgmentHistoryRow[]>(`${BASE}/judgments/${id}/history`),
  metrics: () => api<Metrics>(`${BASE}/metrics`),
};

export const CATEGORY_LABEL: Record<Category, string> = {
  CLEAR: t('의미가 명확함'),
  NAME_ONLY: t('이름만으로 판단 어려움'),
  STATUS_CODE: t('상태 코드'),
  AMOUNT: t('금액·통화'),
  DATE_TIME: t('날짜·시간'),
  STANDARD_LINKED: t('표준 용어 연결'),
  RELATION: t('관계 있음'),
  NO_SOURCE_COMMENT: t('원본 COMMENT 없음'),
  COMMENT_DIFFERS: t('원본 COMMENT ≠ 업무 설명'),
};
export const CATEGORIES = Object.keys(CATEGORY_LABEL) as Category[];

export const MEANING_LABEL: Record<MeaningStatus, string> = { PENDING: tc('평가', '미확인'), CONFIRMED: t('확인함'), UNCONFIRMABLE: t('확인 불가') };
export const JUDGMENT_LABEL: Record<Judgment, string> = { CORRECT: t('맞음'), PARTIAL: t('일부 맞음'), INCORRECT: t('틀림'), NOT_EVALUABLE: t('평가할 수 없음') };
export const UNCERTAINTY_LABEL: Record<UncertaintyJudgment, string> = {
  APPROPRIATE: t('적절함'), MISSED: t('알 수 없는 것을 단정함'), EXCESSIVE: t('알 수 있는 것을 알 수 없다고 함'), NOT_APPLICABLE: t('해당 없음'),
};
export const RECOMMENDATION_LABEL: Record<RecommendationJudgment, string> = {
  APPROPRIATE: t('적절함'), INAPPROPRIATE: t('부적절함'), NO_RECOMMENDATION: t('추천 없음'), NOT_EVALUABLE: t('평가할 수 없음'),
};

/** 비율 표시 : 값 (분자/분모), 분모가 0 이면 - */
export function rateText(r: Rate) {
  return r.base === 0 || r.value == null ? `- (0/0)` : `${(r.value * 100).toFixed(1)}% (${r.count}/${r.base})`;
}
