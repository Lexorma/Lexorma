import type { Edge, Node } from '@xyflow/react';

/**
 * 원본 DB ERD (M-4b) : GET /api/source/connections/{id}/snapshot/erd (SourceErdDto.Graph).
 * 반영 순서 R 에 Lexorma 가 확인해 둔 원본 구조이며 물리 모델(features/erd 의 ErdGraph)과 다른 데이터다 — 두 모델을 섞지 않는다.
 * 관계선은 원본에 정의된 FK 뿐이다 (이름으로 추정하지 않는다).
 */
export interface SourceErdColumn {
  name: string;
  compareName: string;
  dataType: string;
  nullable: boolean | null;
  pk: boolean;
  fk: boolean;
}

export interface SourceErdTable {
  tableId: number;
  schemaId: number;
  name: string;
  compareName: string;
  type: 'TABLE' | 'VIEW';
  comment: string | null;
  columns: SourceErdColumn[];
}

export interface SourceErdRelation {
  id: string;
  name: string | null;
  sourceTableId: number;
  /** 그 시점의 카탈로그에 참조 대상이 없으면(수집 범위 밖) null */
  targetTableId: number | null;
  targetCatalogName: string | null;
  targetSchemaName: string | null;
  targetTableName: string;
  deleteRule: string | null;
  updateRule: string | null;
  /** 복합 FK 의 순서 그대로. …Key 는 노드 컬럼의 비교 이름 */
  columns: { column: string; columnKey: string; referencedColumn: string; referencedColumnKey: string }[];
}

export interface SourceErdSchema {
  schemaId: number;
  catalogName: string | null;
  schemaName: string;
  tableCount: number;
  viewCount: number;
}

export interface SourceErdGraph {
  connectionId: number;
  dbType: string;
  identifierComparison: string;
  reflectedOrder: number | null;
  executionId: number | null;
  reflectedAt: string | null;
  schemas: SourceErdSchema[];
  tables: SourceErdTable[];
  relations: SourceErdRelation[];
}

export type SourceNodeData = {
  table: SourceErdTable;
  schemaName: string;
  /** 여러 스키마가 함께 보일 때 테이블명 앞에 스키마명을 붙인다 */
  showSchema: boolean;
  /** 화면에 그린 관계에 쓰인 컬럼 (비교 이름) : 컬럼 연결점을 만든다 */
  linkedColumns: Set<string>;
  /** 이 테이블의 FK 중 화면 밖(필터로 숨김·수집 범위 밖)을 가리키는 것의 수 */
  hiddenRelations: number;
  related: boolean;
};
export type SourceNode = Node<SourceNodeData, 'sourceTable'>;
export type SourceEdge = Edge<{ relation: SourceErdRelation }>;

export const sourceNodeId = (tableId: number) => `t${tableId}`;
export const tableIdOf = (nodeId: string) => Number(nodeId.slice(1));

/**
 * 한 번에 그리는 테이블·VIEW 수의 한도. 넘으면 스키마를 줄이거나 객체 중심(FK 이웃)으로 보게 한다.
 * 값은 측정으로 정했다 (docs/source-erd-m4b.md 7장 : 자동 배치·그리기 시간).
 */
export const RENDER_LIMIT = 1000;

/**
 * 검색어를 원본 DBMS 의 이름 비교 규칙으로 바꾼다 (SourceIdentifiers.compareName 과 같은 규칙) : 따옴표로 감싸면 그대로,
 * 아니면 PostgreSQL 은 소문자, Oracle 은 대문자, MySQL·MariaDB 는 대소문자를 구분하지 않는 연결일 때만 소문자. 비교 대상은 저장된 비교 이름이다.
 */
export function searchKey(query: string, dbType: string, comparison: string): { text: string; exact: boolean } {
  const trimmed = query.trim();
  const quoted = /^".*"$/.test(trimmed) && trimmed.length > 1;
  const text = quoted ? trimmed.slice(1, -1) : trimmed;
  if (quoted) return { text, exact: true };
  if (dbType === 'POSTGRESQL') return { text: text.toLowerCase(), exact: false };
  if (dbType === 'ORACLE') return { text: text.toUpperCase(), exact: false };
  return { text: comparison === 'CASE_INSENSITIVE' ? text.toLowerCase() : text, exact: false };
}

export function matches(compareName: string, key: { text: string; exact: boolean }) {
  if (!key.text) return true;
  return key.exact ? compareName === key.text : compareName.includes(key.text);
}

/** 테이블에서 FK 를 따라 depth 단계까지 (나가는·들어오는 FK 모두, 스키마와 무관) */
export function neighbors(start: number, relations: SourceErdRelation[], depth: number): Set<number> {
  const adjacent = new Map<number, number[]>();
  for (const r of relations) {
    if (r.targetTableId == null) continue;
    adjacent.set(r.sourceTableId, [...(adjacent.get(r.sourceTableId) ?? []), r.targetTableId]);
    adjacent.set(r.targetTableId, [...(adjacent.get(r.targetTableId) ?? []), r.sourceTableId]);
  }
  const seen = new Set([start]);
  let frontier = [start];
  for (let d = 0; d < depth && frontier.length; d++) {
    const next: number[] = [];
    for (const id of frontier) for (const other of adjacent.get(id) ?? []) if (!seen.has(other)) {
      seen.add(other);
      next.push(other);
    }
    frontier = next;
  }
  return seen;
}

/**
 * 배치 결과 캐시 (이 브라우저 탭에서만) : 연결·시점·보기 범위마다 마지막 좌표를 기억해 같은 범위로 돌아오면 다시 계산하지 않는다.
 * 원본 ERD 의 배치는 서버에 저장하지 않는다 (물리 모델 ERD 의 배치와 무관).
 */
const layoutCache = new Map<string, Map<string, { x: number; y: number }>>();
const CACHE_MAX = 20;

export function cachedLayout(key: string) {
  return layoutCache.get(key);
}

export function rememberLayout(key: string, positions: Map<string, { x: number; y: number }>) {
  layoutCache.delete(key);
  layoutCache.set(key, positions);
  while (layoutCache.size > CACHE_MAX) layoutCache.delete(layoutCache.keys().next().value!);
}
