import { t } from '../../lib/i18n';
import type { EndKind } from './endMarkers';
import type { Cardinality, CardinalityReason, ErdRelation } from './model';

/**
 * 물리 관계의 카디널리티 표기 (M-2 개선 2차 D-08~D-10). 서버가 저장된 제약조건으로 판단한 값만 쓰고, 판단하지 못한 끝은 기호를 그리지 않는다 (0·N 으로 바꾸지 않는다).
 * 끝 기호 : 부모 쪽 = 최소·최대 (최소를 모르면 최대만), 자식 쪽 = 최대만 (자식 최소는 FK 로 확인할 수 없다)
 */
export const PHYSICAL_END_KINDS: EndKind[] = ['one', 'zero-one', 'max-one', 'max-many'];
const PREFIX = 'physical-end';
export const PHYSICAL_END_PREFIX = PREFIX;

export function parentEnd(c: Cardinality | undefined): string | undefined {
  if (!c || c.parentMax !== '1') return undefined;
  return `${PREFIX}-${c.parentMin === '1' ? 'one' : c.parentMin === '0' ? 'zero-one' : 'max-one'}`;
}

export function childEnd(c: Cardinality | undefined): string | undefined {
  if (!c || !c.childMax) return undefined;
  return `${PREFIX}-${c.childMax === '1' ? 'max-one' : 'max-many'}`;
}

export const REASON_TEXT: Record<CardinalityReason, string> = {
  LOGICAL_RELATION: t('논리 관계는 DB FK 제약조건이 아니어서 카디널리티를 판단하지 않습니다.'),
  INVALID_REFERENCE: t('참조 대상 컬럼이 부모 테이블의 PK·UNIQUE 키가 아니어서(무효 FK) 카디널리티를 판단하지 않습니다.'),
  COLUMNS_MISSING: t('관계의 컬럼 대응이 없어 판단할 수 없습니다.'),
  NULLABLE_UNKNOWN: t('FK 컬럼의 NULL 허용 여부가 입력되지 않아 부모 쪽 최소를 판단하지 않습니다.'),
  NULLABLE_MIXED: t('복합 FK 에 NOT NULL 과 NULL 허용 컬럼이 섞여 있고 MATCH 방식(SIMPLE/FULL)을 저장하지 않아 부모 쪽 최소를 판단하지 않습니다.'),
};

export const IDENTIFYING_TEXT: Record<NonNullable<Cardinality['identifying']>, string> = {
  IDENTIFYING: t('식별 관계 (FK 컬럼이 모두 자식 PK)'),
  PARTIAL: t('부분 식별 (FK 컬럼 일부만 자식 PK)'),
  NON_IDENTIFYING: t('비식별 관계 (FK 컬럼이 자식 PK 가 아님)'),
};

/** 부모 쪽 : 자식 행 하나가 가리키는 부모 행 수 */
export function parentText(c: Cardinality) {
  if (c.parentMax == null) return t('판단 불가');
  return c.parentMin == null ? t('최대 1 (최소 판단 불가)') : c.parentMin === '1' ? t('정확히 1 (필수)') : t('0 또는 1 (선택)');
}

/** 자식 쪽 : 부모 행 하나를 가리키는 자식 행 수 */
export function childText(c: Cardinality) {
  return c.childMax == null ? t('판단 불가') : c.childMax === '1' ? t('최대 1') : t('여러 건 가능');
}

/** 관계선 툴팁 : 이름·방향·컬럼 쌍 수·카디널리티 (버전 비교 ERD 처럼 카디널리티가 없으면 생략) */
export function relationTooltip(relation: ErdRelation, c: Cardinality | undefined) {
  const head = `${relation.relationName ?? t('이름 없는 관계')} : ${relation.sourceTableName} → ${relation.targetTableName}`
    + (relation.columns.length > 1 ? ` · ${t('복합 ({0}쌍)', relation.columns.length)}` : '');
  if (!c) return head;
  const lines = [head, `${t('부모 쪽')} : ${parentText(c)}`, `${t('자식 쪽')} : ${childText(c)}`];
  if (c.identifying) lines.push(IDENTIFYING_TEXT[c.identifying]);
  c.reasons.forEach((r) => lines.push(REASON_TEXT[r]));
  return lines.join('\n');
}
