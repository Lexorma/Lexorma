/**
 * ERD PoC 데이터 모델 - M-1 확장 모델(스키마, 관계 엔티티, FK 컬럼 쌍, 관계 유형)을 그대로 담을 수 있는 형태.
 * 현재 서버 모델(ColumnInfo.refTableName 문자열)은 relations 로 변환해서 넣는다 (M-2).
 */
export type RelationKind = 'FK' | 'MODEL' | 'AI_INFERRED' | 'APPROVED';

export const RELATION_KIND_LABEL: Record<RelationKind, string> = {
  FK: '실제 FK',
  MODEL: '모델 관계',
  AI_INFERRED: 'AI 추정',
  APPROVED: '승인된 관계',
};

export interface ErdColumn {
  id: string;
  name: string;
  dataType: string;
  pk: boolean;
  nullable: boolean;
}

export interface ErdTable {
  id: string;
  schema: string;
  name: string;
  columns: ErdColumn[];
}

/** 복합 관계는 컬럼 쌍이 여러 개 */
export interface ErdColumnPair {
  sourceColumnId: string;
  targetColumnId: string;
}

export interface ErdRelation {
  id: string;
  name: string;
  kind: RelationKind;
  sourceTableId: string;
  targetTableId: string;
  columns: ErdColumnPair[];
}

export interface ErdModel {
  tables: ErdTable[];
  relations: ErdRelation[];
}

export interface XY {
  x: number;
  y: number;
}

/** 저장·복원 대상 (M-2 에서 모델과 별도 테이블에 저장) */
export interface ErdLayoutState {
  nodes: Record<string, XY>;
  viewport: { x: number; y: number; zoom: number };
}

// 노드 크기는 CSS(erd.css)와 같은 상수로 계산한다 → 렌더링 전에 레이아웃 엔진에 넘길 수 있다
export const NODE_WIDTH = 240;
export const HEADER_HEIGHT = 30;
export const ROW_HEIGHT = 22;

export function visibleColumns(table: ErdTable, fkIds: Set<string>, keyOnly: boolean) {
  return keyOnly ? table.columns.filter((c) => c.pk || fkIds.has(c.id)) : table.columns;
}

export function nodeHeight(rowCount: number) {
  return HEADER_HEIGHT + rowCount * ROW_HEIGHT + 2;
}
