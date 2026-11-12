import type { Edge, Node } from '@xyflow/react';

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
}

export interface ErdGraph {
  tables: ErdTable[];
  columns: ErdColumn[];
  relations: ErdRelation[];
  totalCount: number;
  truncated: boolean;
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

export type TableNodeData = {
  table: ErdTable;
  columns: ErdColumn[];
  /** FK 관계의 원본 컬럼 */
  fkColumns: Set<number>;
  /** 화면에 그리는 관계에 쓰인 컬럼 (연결점을 만든다) */
  linkedColumns: Set<number>;
};
export type TableNode = Node<TableNodeData, 'table'>;
export type RelationEdge = Edge<{ relation: ErdRelation }>;

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
