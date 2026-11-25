import { api, enc } from './api';
import { t } from './i18n';

/**
 * 삭제·승인·매핑 기준값 (M8-C2, docs/completed/modeling-concurrency-m8.md 14장).
 * - 삭제·승인 요청은 화면이 읽은 수정 일시(modifiedAt)를 보낸다 — 그 사이 다른 사용자가 바꿨으면 서버가 409 MODEL_CONFLICT (공통 오류 알림).
 * - 하위 객체를 함께 지우는 삭제(엔터티·논리 모델·테이블)는 삭제 확인 조회가 준 구성 기준값(composition)도 보낸다.
 *   확인 대화상자에 함께 지워질 수를 보여 주고, 확인한 뒤 그 구성이 바뀌었으면 409 다.
 * 409 뒤 다시 읽기·다시 적용 화면은 M8-C3 — 지금은 공통 오류 알림만 (실패를 성공으로 표시하지 않는다).
 */
export interface Deletion {
  id: number;
  name: string | null;
  modifiedAt: string | null;
  cascade: { kind: string; count: number }[];
  composition: string;
}

const KIND_LABELS: Record<string, () => string> = {
  attributes: () => t('속성'),
  identifiers: () => t('식별자'),
  subjectAreas: () => t('주제 영역'),
  indexes: () => t('인덱스'),
  constraints: () => t('제약조건'),
  relations: () => t('관계'),
  conversions: () => t('변환 기록'),
};

/** 함께 지워질 자식이 있으면 "속성 2개, 식별자 1개도 함께 삭제됩니다." (없으면 빈 문자열) */
export function cascadeText(deletions: Deletion | Deletion[]): string {
  const totals = new Map<string, number>();
  (Array.isArray(deletions) ? deletions : [deletions]).forEach((d) => d.cascade.forEach((c) => totals.set(c.kind, (totals.get(c.kind) ?? 0) + c.count)));
  const parts = [...totals].filter(([, count]) => count > 0).map(([kind, count]) => t('{0} {1}개', KIND_LABELS[kind]?.() ?? kind, count));
  return parts.length ? t('{0}도 함께 삭제됩니다.', parts.join(', ')) : '';
}

/** 삭제 확인 조회 (GET) 뒤 확인 대화상자를 연다. 조회 오류(대상 없음 등)는 공통 오류 알림이 보여 주고 대화상자를 열지 않는다 */
export function withDeletion(url: string, open: (deletion: Deletion) => void): void {
  api<Deletion>(url).then(open, () => undefined);
}

/** 쿼리 문자열 : ?modifiedAt=…[&composition=…] (값이 없으면 빈 값을 보내 서버가 400 MODEL_BASE_REQUIRED 로 알린다) */
export function baseQuery(modifiedAt: string | null | undefined, composition?: string | null): string {
  return `?modifiedAt=${enc(modifiedAt)}${composition === undefined ? '' : `&composition=${enc(composition)}`}`;
}
