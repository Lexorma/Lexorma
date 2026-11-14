import { t } from '../../lib/i18n';

/** ViewDto.Column : 사용자가 관리하는 출력 컬럼 (순서 = 목록 순서, 타입은 비워 둘 수 있다) */
export interface ViewColumn {
  columnName: string;
  columnKorName: string | null;
  dataType: string | null;
  columnDescription: string | null;
}

/**
 * ViewDto.Reference : 내부 참조(tableId 또는 viewId) / 미확인 참조(두 ID 가 없고 이름만).
 * 응답의 schemaName·objectName 은 그 시점의 이름 (내부 참조도 표시용으로 함께 온다)
 */
export interface ViewReference {
  referenceType: 'TABLE' | 'VIEW';
  tableId: number | null;
  viewId: number | null;
  schemaName: string | null;
  objectName: string | null;
}

/** ViewDto.Definition : VIEW 정의 전체 (상세의 확정 정의, 변경 요청 전후) */
export interface ViewDefinition {
  schemaId: number | null;
  schemaName: string | null;
  viewName: string;
  viewKorName: string | null;
  viewDescription: string | null;
  definition: string;
  columns: ViewColumn[];
  references: ViewReference[];
}

export interface ViewChangeRequest {
  id: number;
  viewId: number;
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED';
  before: ViewDefinition;
  after: ViewDefinition;
  requestedBy: string | null;
  requestedAt: string | null;
  processedBy: string | null;
  processedAt: string | null;
}

export interface ViewDetail {
  id: number;
  isApproval: boolean | null;
  view: ViewDefinition;
  created: string | null;
  updated: string | null;
  createdBy: string | null;
  updatedBy: string | null;
  requests: ViewChangeRequest[];
  /** 이 VIEW 를 참조하는 다른 VIEW 이름 (있으면 삭제할 수 없다) */
  referencedBy: string[];
}

export interface ViewSaveResult {
  id: number;
  applied: boolean;
  request: ViewChangeRequest | null;
}

/** 참조 객체 후보 (GET /api/physical/view/targets) */
export interface ViewTarget {
  referenceType: 'TABLE' | 'VIEW';
  id: number;
  schemaId: number | null;
  schemaName: string | null;
  name: string;
  korName: string | null;
}

/** 물리 ERD 의 VIEW 노드 (GET /api/erd/views) */
export interface ErdView {
  id: number;
  schemaId: number;
  viewName: string;
  viewKorName: string | null;
  isApproval: boolean | null;
  columns: ViewColumn[];
  references: ViewReference[];
}

export const isInternal = (r: ViewReference) => r.tableId != null || r.viewId != null;

export const referenceTypeLabel = (type: ViewReference['referenceType']) => (type === 'VIEW' ? 'VIEW' : t('테이블'));

/** 참조 객체 표시 : 스키마명.이름 (미확인은 이름 그대로) */
export const referenceName = (r: ViewReference) => `${r.schemaName ? `${r.schemaName}.` : ''}${r.objectName ?? (r.tableId != null ? `#${r.tableId}` : `#${r.viewId}`)}`;

/** 공백·줄바꿈을 하나로 본 SQL : 변경 비교에서 "공백만 다름" 을 알릴 때만 쓴다 (저장·DDL 은 원문) */
export const normalizedSql = (sql: string) => sql.replace(/\s+/g, ' ').trim();

/** VIEW 관리 화면 경로 (상세를 바로 연다) */
export const viewManagePath = (viewId: number) => `/viewManage?viewId=${viewId}`;
