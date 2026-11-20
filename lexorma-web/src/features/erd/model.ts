import type { Edge, Node } from '@xyflow/react';
import { t } from '../../lib/i18n';
import type { Lane } from './relationLanes';

/** GET /api/erd, /api/erd/neighbors/{tableId} (ErdDto.Graph) */
export interface ErdTable {
  id: number;
  schemaId: number;
  tableName: string;
  tableKorName: string | null;
  tableDescription: string | null;
  isApproval: boolean | null;
  isMasterTable: boolean | null;
}

export interface ErdColumn {
  id: number;
  tableId: number;
  columnName: string;
  termName: string | null;
  columnDesc: string | null;
  dataType: string | null;
  maxLength: number | null;
  precision: number | null;
  scale: number | null;
  isNullable: boolean | null;
  isPk: boolean | null;
  isApproval: boolean | null;
  /** 표준 용어의 표준 도메인명 (용어가 없거나 삭제됐으면 null) */
  domainName: string | null;
  /** 단일 컬럼 UNIQUE · 필수 입력 (M-2 개선 2차에 응답에 더해졌다) */
  isUnique?: boolean | null;
  isRequired?: boolean | null;
}

/** PhysicalModelDto.Relation : 컬럼 쌍은 복합 FK 순서대로. 상대 테이블은 조회 범위 밖일 수 있다 */
export interface ErdRelation {
  id: number;
  relationName: string | null;
  relationType: 'FK' | 'LOGICAL';
  relationSource: 'USER' | 'REF_COLUMN' | 'IMPORTED' | 'AI_APPROVED';
  sourceTableId: number;
  sourceTableName: string;
  targetTableId: number;
  targetTableName: string;
  columns: { sourceColumnId: number; sourceColumnName: string; targetColumnId: number; targetColumnName: string }[];
  isApproval: boolean | null;
  /** 수정 기준값 (DEC-08) : FK 규칙 저장이 돌려준다 */
  modifiedAt?: string | null;
  /** FK 의 ON DELETE · ON UPDATE (V7). 비면 NO_ACTION */
  deleteRule?: FkRule | null;
  updateRule?: FkRule | null;
}

export type FkRule = 'NO_ACTION' | 'RESTRICT' | 'CASCADE' | 'SET_NULL' | 'SET_DEFAULT';
export const FK_RULES: FkRule[] = ['NO_ACTION', 'RESTRICT', 'CASCADE', 'SET_NULL', 'SET_DEFAULT'];
/** 규칙 표시 : SQL 표기 그대로 (NO ACTION 등) */
export const fkRuleText = (rule: FkRule | null | undefined) => (rule ?? 'NO_ACTION').replace('_', ' ');

/**
 * 물리 제약조건으로 판단한 관계 하나의 카디널리티 (ErdDto.Cardinality, M-2 개선 2차 D-08~D-10). 자식 = 참조하는 테이블, 부모 = 참조되는 테이블.
 * 판단하지 못한 끝은 null 이고 reasons 에 사유 코드가 있다. 자식 쪽 최소는 FK 로 보장되지 않아 늘 판단하지 않는다
 */
export interface Cardinality {
  parentMin: '0' | '1' | null;
  parentMax: '1' | null;
  childMax: '1' | 'N' | null;
  identifying: 'IDENTIFYING' | 'PARTIAL' | 'NON_IDENTIFYING' | null;
  reasons: CardinalityReason[];
}
export type CardinalityReason = 'LOGICAL_RELATION' | 'INVALID_REFERENCE' | 'COLUMNS_MISSING' | 'NULLABLE_UNKNOWN' | 'NULLABLE_MIXED';

/**
 * relationScope=INNER 로 받은 응답 : relations 는 양 끝이 범위 안인 관계만, outsideRelations 는 범위 안 테이블 ID → 범위 밖 상대와의 관계 수 (FK 1개 = 1),
 * fkColumnIds 는 범위 안 테이블에서 FK 를 이루는 컬럼 (상대가 범위 밖이어도), cardinalities 는 relations 의 관계 ID → 카디널리티
 */
export interface ErdGraph {
  tables: ErdTable[];
  columns: ErdColumn[];
  relations: ErdRelation[];
  totalCount: number;
  truncated: boolean;
  outsideRelations?: Record<string, number> | null;
  fkColumnIds?: number[] | null;
  cardinalities?: Record<string, Cardinality>;
}

/** GET /api/erd/tables : 테이블 찾기 */
export interface TableHits {
  items: { id: number; schemaId: number; schemaName: string; tableName: string; tableKorName: string | null }[];
  totalCount: number;
}

/** GET /api/erd/tables/{id}/relations : 테이블 하나의 관계 목록 한 페이지 */
export interface RelationPage {
  items: ErdRelation[];
  cardinalities: Record<string, Cardinality>;
  tables: Record<string, ErdTable>;
  totalCount: number;
}

export interface XY {
  x: number;
  y: number;
}

/** GET/PUT /api/erd/layout : 저장한 적 없으면 version·viewport null */
export interface ErdLayout {
  version: number | null;
  nodes: Record<string, XY>;
  viewport: { x: number; y: number; zoom: number } | null;
}

export interface Schema {
  id: number;
  schemaName: string;
  isDefault: boolean;
}

/**
 * 버전 비교 ERD (M-10 ③) 의 변경 표시. 보통의 ERD 노드에는 없다.
 * change : 노드에 붙이는 표시 (자신 또는 딸린 행이 달라졌으면 MODIFIED), rows : 행(컬럼·속성) ID → 변경, caption : 부제 앞에 붙이는 말 (스키마명·모델명)
 */
export type DiffKind = 'ADDED' | 'REMOVED' | 'MODIFIED';
export interface NodeDiff {
  change: DiffKind | null;
  rows: Map<number, DiffKind>;
  caption?: string;
}
/** 색만으로 구분하지 않도록 기호와 글자를 함께 쓴다 */
export const DIFF_MARK: Record<DiffKind, string> = { ADDED: '+', REMOVED: '−', MODIFIED: '~' };
export const DIFF_LABEL: Record<DiffKind, string> = { ADDED: t('추가'), REMOVED: t('삭제'), MODIFIED: t('수정') };
export const DIFF_COLOR: Record<DiffKind, string> = { ADDED: 'var(--gh-success)', REMOVED: 'var(--gh-danger)', MODIFIED: 'var(--gh-accent)' };
export const diffClass = (change: DiffKind | null | undefined) => (change ? ` erd-diff-${change.toLowerCase()}` : '');

export type TableNodeData = {
  table: ErdTable;
  columns: ErdColumn[];
  /** FK 관계의 원본 컬럼 */
  fkColumns: Set<number>;
  /** 모델 구조 진단의 무효 FK 수 (DEC-09, 물리 ERD 만) — 0·없으면 표시하지 않는다 */
  invalidFk?: number;
  /** 화면에 그리는 관계에 쓰인 컬럼 (연결점을 만든다) */
  linkedColumns: Set<number>;
  diff?: NodeDiff;
  /** 같은 이름의 테이블이 다른 스키마에도 보일 때 이름 앞에 붙일 스키마명 (테이블명은 스키마 안에서만 하나다) */
  schemaPrefix?: string;
};
export type TableNode = Node<TableNodeData, 'table'>;
/** 관계선 하나 = FK 제약조건 하나 (M-2 개선 2차 D-07). lane : 같은 두 테이블(자기 참조는 한 테이블) 사이에서 관계 ID 순 몇 번째인지, tableEnds : 테이블 연결점에 붙었는지 */
export type RelationEdge = Edge<{ relation: ErdRelation; lane: Lane; tableEnds: boolean; tooltip: string }, 'fk'>;

export const NODE_WIDTH = 280;
export const HEADER_HEIGHT = 52;
export const ROW_HEIGHT = 24;
/** 노드에 그리는 최대 컬럼 수 (나머지는 상세 패널). 넘으면 안내 행 1줄 */
export const MAX_ROWS = 20;
/** 화면에서 노드 너비가 이보다 작으면 컬럼을 그리지 않는다 (M-0 PoC 측정값) */
export const SIMPLE_WIDTH_PX = 140;

export const nodeHeight = (columnCount: number) =>
  HEADER_HEIGHT + Math.min(columnCount, MAX_ROWS) * ROW_HEIGHT + (columnCount > MAX_ROWS ? ROW_HEIGHT : 0);

export function columnType(c: ErdColumn) {
  if (!c.dataType || c.dataType.includes('(')) return c.dataType ?? '';
  if (c.precision != null) return `${c.dataType}(${c.precision}${c.scale != null ? `,${c.scale}` : ''})`;
  return c.maxLength != null ? `${c.dataType}(${c.maxLength})` : c.dataType;
}

/** 테이블 ID 별 컬럼 (조회 순서 = 컬럼 정렬 순서 유지) */
export function columnsByTable(columns: ErdColumn[]) {
  const map = new Map<number, ErdColumn[]>();
  columns.forEach((c) => map.set(c.tableId, [...(map.get(c.tableId) ?? []), c]));
  return map;
}
