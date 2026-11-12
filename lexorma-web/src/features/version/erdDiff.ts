import type { ErdColumn, ErdRelation, DiffKind, NodeDiff, TableNode } from '../erd/model';
import { MAX_ROWS } from '../erd/model';
import type { EntityNodeType } from '../logical/erdNodes';
import type { GraphAttribute, LogicalRelationship } from '../logical/model';
import type { HistoryState } from '../history/model';
import type { VersionChange, VersionComparison } from './model';

/**
 * 버전 비교 ERD (M-10 ③) : 서버 ModelVersionDto.ErdComparison 과 같은 모양, 그리고 그것을 변경 전·변경 후 ERD 로 바꾸는 계산.
 * 판정(추가·삭제·수정)은 서버의 버전 비교가 한 것을 그대로 쓴다. 여기서는 저장된 상태를 기존 ERD 노드·관계선의 모양으로 옮기기만 한다.
 */
export type ErdKind = 'PHYSICAL' | 'LOGICAL';

/** 비교한 객체 하나. 그리는 데 필요한 달라지지 않은 객체도 UNCHANGED 로 온다 */
export type ErdObject = Omit<VersionChange, 'change'> & { change: DiffKind | 'UNCHANGED' };

export interface ErdComparisonNode {
  /** 테이블 · 엔터티 */
  node: ErdObject;
  /** 컬럼 · 속성 (달라지지 않은 것 포함) */
  items: ErdObject[];
  /** 달라진 인덱스·제약조건 / 엔터티의 식별자 전부 */
  extras: ErdObject[];
}

export interface ErdComparison {
  base: VersionComparison['base'];
  target: VersionComparison['target'];
  kind: ErdKind;
  /** 달라졌거나 달라진 관계가 걸린 노드 수 (범위에 들지 못한 것 포함) */
  changedNodeCount: number;
  truncated: boolean;
  nodes: ErdComparisonNode[];
  /** 범위 안의 노드에 한쪽이라도 걸린 관계 */
  edges: ErdObject[];
}

export type Side = 'before' | 'after';

const KINDS: Record<ErdKind, { node: string; edge: string; parent: string; source: string; target: string }> = {
  PHYSICAL: { node: 'PHYSICAL_TABLE', edge: 'PHYSICAL_RELATION', parent: 'tableInfoId', source: 'sourceTableId', target: 'targetTableId' },
  LOGICAL: { node: 'LOGICAL_ENTITY', edge: 'LOGICAL_RELATIONSHIP', parent: 'entityId', source: 'sourceEntityId', target: 'targetEntityId' },
};

/** 대상 종류 → 그 객체를 볼 수 있는 ERD. 스키마·논리 모델·매핑은 ERD 에 그리지 않는다 (목록의 상세 비교로 본다) */
export function erdKindOf(targetType: string): ErdKind | null {
  if (targetType.startsWith('PHYSICAL_')) return 'PHYSICAL';
  return targetType.startsWith('LOGICAL_') && targetType !== 'LOGICAL_MODEL' ? 'LOGICAL' : null;
}

const num = (value: unknown) => (typeof value === 'number' ? value : null);
const str = (value: unknown) => (typeof value === 'string' ? value : null);
const bool = (value: unknown) => (typeof value === 'boolean' ? value : null);
const list = (value: unknown) => (Array.isArray(value) ? (value as HistoryState[]) : []);

export const isChanged = (object: ErdObject): object is ErdObject & { change: DiffKind } => object.change !== 'UNCHANGED';

/** 테이블 이름을 바꾸면 그 테이블의 모든 컬럼이 들고 있는 테이블명도 따라 바뀐다 → 컬럼 자신의 변경으로 강조하지 않는다 (목록에는 그대로 나온다) */
const DERIVED = new Set(['tableName']);

/** 행(컬럼·속성)에 붙이는 표시 */
export function rowChange(item: ErdObject): DiffKind | null {
  if (item.change === 'UNCHANGED') return null;
  if (item.change === 'MODIFIED' && item.changedFields.every((field) => DERIVED.has(field))) return null;
  return item.change;
}

/** 노드에 붙이는 표시 : 자신이 추가·삭제·수정됐거나, 딸린 컬럼·속성·인덱스·제약조건·식별자가 달라졌으면 수정 */
export function nodeChange(entry: ErdComparisonNode): DiffKind | null {
  if (entry.node.change === 'ADDED' || entry.node.change === 'REMOVED') return entry.node.change;
  return entry.node.change === 'MODIFIED' || entry.items.some(rowChange) || entry.extras.some(isChanged) ? 'MODIFIED' : null;
}

/** 관계의 양 끝 (변경 전후 어느 쪽이든) */
export function edgeEnds(kind: ErdKind, edge: ErdObject): number[] {
  const ids = [edge.before, edge.after].flatMap((state) => (state ? [num(state[KINDS[kind].source]), num(state[KINDS[kind].target])] : []));
  return [...new Set(ids.filter((id): id is number => id !== null))];
}

/** 목록에서 고른 객체 → 그것을 보여 줄 노드와 선택 대상. 범위에 없으면 null (서버에 그 객체부터 다시 요청한다) */
export function locate(data: ErdComparison, targetType: string, targetId: number): { nodeIds: number[]; selection: Selection } | null {
  const shown = new Set(data.nodes.map((entry) => entry.node.targetId));
  if (targetType === KINDS[data.kind].node) return shown.has(targetId) ? { nodeIds: [targetId], selection: { type: 'node', id: targetId } } : null;
  if (targetType === KINDS[data.kind].edge) {
    const edge = data.edges.find((item) => item.targetId === targetId);
    const ends = edge ? edgeEnds(data.kind, edge).filter((id) => shown.has(id)) : [];
    return ends.length ? { nodeIds: ends, selection: { type: 'edge', id: targetId } } : null;
  }
  const owner = data.nodes.find((entry) => [...entry.items, ...entry.extras].some((item) => item.targetType === targetType && item.targetId === targetId));
  return owner ? { nodeIds: [owner.node.targetId], selection: { type: 'node', id: owner.node.targetId } } : null;
}

export interface Selection {
  type: 'node' | 'edge';
  id: number;
}

/** 한쪽(변경 전 또는 변경 후)의 ERD : 기존 물리·논리 ERD 의 노드와 관계 모양 그대로 */
export type SideModel =
  | { kind: 'PHYSICAL'; nodes: TableNode[]; columns: ErdColumn[]; relations: ErdRelation[]; edgeChange: Map<number, DiffKind> }
  | { kind: 'LOGICAL'; nodes: EntityNodeType[]; relationships: LogicalRelationship[]; edgeChange: Map<number, DiffKind> };

/** 정렬 순서대로, 다 그리지 못할 만큼 많으면 달라진 행이 먼저 보이게 */
function ordered(items: ErdObject[], side: Side): ErdObject[] {
  const rows = [...items].sort((a, b) => (num(a[side]?.sortOrder) ?? Number.MAX_SAFE_INTEGER) - (num(b[side]?.sortOrder) ?? Number.MAX_SAFE_INTEGER) || a.targetId - b.targetId);
  return rows.length > MAX_ROWS ? [...rows.filter(rowChange), ...rows.filter((row) => !rowChange(row))] : rows;
}

function rowsOf(entry: ErdComparisonNode, side: Side, parent: string): ErdObject[] {
  return ordered(entry.items.filter((item) => num(item[side]?.[parent]) === entry.node.targetId), side);
}

function diffOf(entry: ErdComparisonNode, rows: ErdObject[], caption?: string | null): NodeDiff {
  const marks = new Map<number, DiffKind>();
  rows.forEach((row) => { const change = rowChange(row); if (change) marks.set(row.targetId, change); });
  return { change: nodeChange(entry), rows: marks, caption: caption ?? undefined };
}

/** 이쪽에 있는 관계 (양 끝이 모두 그려지는 것만 선으로 그린다) */
function sideEdges(data: ErdComparison, side: Side, shown: Set<number>) {
  const { source, target } = KINDS[data.kind];
  return data.edges.filter((edge) => {
    const state = edge[side];
    return !!state && shown.has(num(state[source]) ?? -1) && shown.has(num(state[target]) ?? -1);
  });
}

function edgeChanges(edges: ErdObject[]) {
  return new Map(edges.filter(isChanged).map((edge) => [edge.targetId, edge.change]));
}

export function sideModel(data: ErdComparison, side: Side): SideModel {
  const entries = data.nodes.filter((entry) => entry.node[side]);
  const shown = new Set(entries.map((entry) => entry.node.targetId));
  const edges = sideEdges(data, side, shown);
  return data.kind === 'PHYSICAL' ? physicalSide(data, side, entries, edges) : logicalSide(side, entries, edges);
}

function physicalSide(data: ErdComparison, side: Side, entries: ErdComparisonNode[], edges: ErdObject[]): SideModel {
  const relations: ErdRelation[] = edges.map((edge) => {
    const state = edge[side]!;
    return {
      id: edge.targetId,
      relationName: str(state.relationName),
      relationType: state.relationType === 'LOGICAL' ? 'LOGICAL' : 'FK',
      relationSource: (str(state.relationSource) ?? 'USER') as ErdRelation['relationSource'],
      sourceTableId: num(state.sourceTableId)!,
      sourceTableName: str(state.sourceTableName) ?? '',
      targetTableId: num(state.targetTableId)!,
      targetTableName: str(state.targetTableName) ?? '',
      columns: list(state.columns).map((pair) => ({
        sourceColumnId: num(pair.sourceColumnId)!, sourceColumnName: str(pair.sourceColumnName) ?? '',
        targetColumnId: num(pair.targetColumnId)!, targetColumnName: str(pair.targetColumnName) ?? '',
      })),
      isApproval: bool(state.isApproval),
    };
  });
  // FK 표시는 선으로 그리지 못하는 관계(상대 테이블이 범위 밖)의 컬럼에도 붙인다
  const fkColumns = new Set(data.edges.flatMap((edge) => (edge[side]?.relationType === 'FK' ? list(edge[side]!.columns).map((pair) => num(pair.sourceColumnId) ?? -1) : [])));
  const linkedColumns = new Set(relations.flatMap((relation) => relation.columns.flatMap((pair) => [pair.sourceColumnId, pair.targetColumnId])));

  const columns: ErdColumn[] = [];
  const nodes: TableNode[] = entries.map((entry) => {
    const state = entry.node[side]!;
    const rows = rowsOf(entry, side, 'tableInfoId');
    const own = rows.map((row): ErdColumn => {
      const column = row[side]!;
      return {
        id: row.targetId, tableId: entry.node.targetId, columnName: str(column.columnName) ?? '', termName: null, columnDesc: str(column.columnDesc),
        dataType: str(column.dataType), maxLength: num(column.maxLength), precision: num(column.precision), scale: num(column.scale),
        isNullable: bool(column.isNullable), isPk: bool(column.isPk), isApproval: bool(column.isApproval), domainName: null,
      };
    });
    columns.push(...own);
    return {
      id: String(entry.node.targetId),
      type: 'table',
      position: { x: 0, y: 0 },
      data: {
        table: {
          id: entry.node.targetId, schemaId: num(state.schemaId) ?? 0, tableName: str(state.tableName) ?? '', tableKorName: str(state.tableKorName),
          tableDescription: str(state.tableDescription), isApproval: bool(state.isApproval), isMasterTable: bool(state.isMasterTable),
        },
        columns: own,
        fkColumns,
        linkedColumns,
        // 소속 스키마가 바뀐 테이블은 그 시점의 스키마명을 함께 적는다
        diff: diffOf(entry, rows, entry.node.changedFields.includes('schemaId') ? str(state.schemaName) : null),
      },
    };
  });
  return { kind: 'PHYSICAL', nodes, columns, relations, edgeChange: edgeChanges(edges) };
}

function logicalSide(side: Side, entries: ErdComparisonNode[], edges: ErdObject[]): SideModel {
  const relationships: LogicalRelationship[] = edges.map((edge) => {
    const state = edge[side]!;
    return {
      id: edge.targetId, modelId: num(state.modelId) ?? 0, relationshipName: str(state.relationshipName), relationshipDescription: str(state.relationshipDescription),
      sourceEntityId: num(state.sourceEntityId)!, sourceEntityName: str(state.sourceEntityName) ?? '', targetEntityId: num(state.targetEntityId)!, targetEntityName: str(state.targetEntityName) ?? '',
      isIdentifying: bool(state.isIdentifying), sourceMin: num(state.sourceMin) ?? 0, sourceMax: num(state.sourceMax), targetMin: num(state.targetMin) ?? 0, targetMax: num(state.targetMax),
      cardinality: '', isApproval: bool(state.isApproval),
    };
  });
  const nodes: EntityNodeType[] = entries.map((entry) => {
    const state = entry.node[side]!;
    const rows = rowsOf(entry, side, 'entityId');
    // 식별자 표시 : 이쪽 시점의 식별자 구성으로 (PI 주 식별자 / AI 보조 식별자)
    const identifiers = entry.extras.filter((extra) => extra.targetType === 'LOGICAL_IDENTIFIER' && extra[side]);
    const inIdentifier = new Set(identifiers.flatMap((identifier) => list(identifier[side]!.attributes).map((attribute) => num(attribute.attributeId))));
    const inPrimary = new Set(identifiers.filter((identifier) => identifier[side]!.isPrimary === true).flatMap((identifier) => list(identifier[side]!.attributes).map((attribute) => num(attribute.attributeId))));
    return {
      id: String(entry.node.targetId),
      type: 'entity',
      position: { x: 0, y: 0 },
      data: {
        entity: {
          id: entry.node.targetId, name: str(state.entityName) ?? '', description: str(state.entityDescription), isApproval: bool(state.isApproval),
          attributes: rows.map((row): GraphAttribute => {
            const attribute = row[side]!;
            return {
              id: row.targetId, name: str(attribute.attributeName) ?? '', dataType: str(attribute.dataType), isRequired: bool(attribute.isRequired), sortOrder: num(attribute.sortOrder),
              termId: num(attribute.standardTermId), termName: str(attribute.standardTermName), domainName: null,
              identifier: inIdentifier.has(row.targetId), primaryIdentifier: inPrimary.has(row.targetId),
            };
          }),
        },
        // 여러 논리 모델의 엔터티를 한 화면에 그리므로 모델명을 함께 적는다
        diff: diffOf(entry, rows, str(state.modelName)),
      },
    };
  });
  return { kind: 'LOGICAL', nodes, relationships, edgeChange: edgeChanges(edges) };
}

/** 노드에 그리는 행 수 (변경 전후 중 많은 쪽) : 두 화면이 같은 배치를 쓰므로 큰 쪽에 맞춘다 */
export function rowCount(kind: ErdKind, entry: ErdComparisonNode): number {
  const parent = KINDS[kind].parent;
  return Math.max(...(['before', 'after'] as const).map((side) => entry.items.filter((item) => num(item[side]?.[parent]) === entry.node.targetId).length));
}

/** 자동 배치에 쓰는 연결 : 변경 전후 어느 쪽에든 있는 관계 */
export function layoutLinks(data: ErdComparison): { source: string; target: string }[] {
  const { source, target } = KINDS[data.kind];
  const shown = new Set(data.nodes.map((entry) => entry.node.targetId));
  return data.edges.flatMap((edge) => [edge.before, edge.after].flatMap((state) => {
    const from = num(state?.[source]), to = num(state?.[target]);
    return from !== null && to !== null && shown.has(from) && shown.has(to) ? [{ source: String(from), target: String(to) }] : [];
  }));
}
