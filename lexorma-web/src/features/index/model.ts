import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';

export interface ColumnRef {
  columnId: number;
  columnName?: string | null;
}

/** 검색 키 하나 : 컬럼(columnId) 또는 표현식(expression). direction ASC/DESC, nulls null(기본)/FIRST/LAST */
export interface Member {
  columnId: number | null;
  columnName?: string | null;
  expression: string | null;
  direction: 'ASC' | 'DESC' | null;
  nulls: 'FIRST' | 'LAST' | null;
}

/**
 * PhysicalModelDto.Index : indexSource USER(직접 등록) / COLUMN(컬럼의 인덱스 여부에서 만든 인덱스).
 * 전체 구성은 members·includeColumns·condition (columns 는 키 중 컬럼만, 예전 응답 호환)
 */
export interface TableIndex {
  id: number;
  tableId: number;
  indexName: string;
  indexType: string | null;
  isUnique: boolean;
  columns: ColumnRef[];
  isApproval: boolean | null;
  indexSource: 'USER' | 'COLUMN';
  members?: Member[] | null;
  includeColumns?: ColumnRef[] | null;
  condition?: string | null;
}

/** 변경 요청의 전후 정의 (M-14 1차 요청은 members 등이 없다 → columns 가 오름차순 키) */
export interface IndexDefinition {
  indexName: string;
  indexType: string | null;
  isUnique: boolean;
  columns: ColumnRef[];
  members?: Member[] | null;
  includeColumns?: ColumnRef[] | null;
  condition?: string | null;
}

/** 키 표시 : 표현식은 괄호, 정렬은 DDL 과 같은 표기 (ASC·기본 NULLS 는 생략) */
export function memberText(member: Member, columnName?: (id: number) => string | undefined): string {
  const head = member.expression != null ? `(${member.expression})` : (member.columnName ?? columnName?.(member.columnId!) ?? `#${member.columnId}`);
  const desc = member.direction === 'DESC';
  const sort = desc ? (member.nulls === 'LAST' ? ' DESC NULLS LAST' : ' DESC') : member.nulls === 'FIRST' ? ' NULLS FIRST' : '';
  return head + sort;
}

/** 키 표시에 필요한 부분 (인덱스·변경 요청 정의·ERD 의 인덱스가 모두 맞는다) */
type Definable = { columns: ColumnRef[]; members?: Member[] | null; includeColumns?: ColumnRef[] | null };

/** 정의의 키 목록 (예전 요청은 columns) */
export function definitionMembers(d: Definable): Member[] {
  return d.members?.length ? d.members : d.columns.map((c) => ({ columnId: c.columnId, columnName: c.columnName, expression: null, direction: 'ASC', nulls: null }));
}

export const keysText = (d: Definable) => definitionMembers(d).map((m) => memberText(m)).join(', ');
export const includeText = (d: Definable) => (d.includeColumns ?? []).map((c) => c.columnName ?? `#${c.columnId}`).join(', ');

export interface ChangeRequest {
  id: number;
  indexId: number;
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED';
  before: IndexDefinition;
  after: IndexDefinition;
  requestedBy: string | null;
  requestedAt: string | null;
  processedBy: string | null;
  processedAt: string | null;
}

export interface IndexDetail {
  index: TableIndex;
  schemaId: number | null;
  schemaName: string | null;
  tableName: string;
  created: string | null;
  updated: string | null;
  createdBy: string | null;
  updatedBy: string | null;
  requests: ChangeRequest[];
  /** USER UNIQUE 단일 컬럼 인덱스와 같은 컬럼의 컬럼 인덱스 : 미승인이면 승인 때 대신(삭제)될 것, 승인 상태면 예전부터 공존하는 정리 대상 */
  replacedColumnIndexes: string[];
}

export interface IndexSaveResult {
  index: TableIndex;
  applied: boolean;
  request: ChangeRequest | null;
}

/** 프로젝트 대상 DBMS 에서 DDL 로 낼 수 있는 인덱스 유형 (비우면 DBMS 기본) */
export function useIndexTypes() {
  const { user } = useAuth();
  return useQuery({
    queryKey: ['index-types', user?.projectId],
    queryFn: () => api<{ dbType: string; dbLabel: string; types: string[] }>('/api/physical/index/types'),
  });
}

/** 인덱스 관리 화면 경로 (테이블 조건) */
export const indexManagePath = (tableId: number) => `/indexManage?tableId=${tableId}`;
