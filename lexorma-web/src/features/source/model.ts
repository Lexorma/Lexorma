import type { LabelVariant } from '../../components/grid/cells';
import { t } from '../../lib/i18n';

/**
 * 원본 DB 관리 (M-4a) : lexorma-source 의 API 응답 (SourceConnectionDto · SourceCollectionDto · SourceCatalogDto).
 * 원본 카탈로그는 외부 DB 에서 수집한 사실이고 물리 모델(TB_TBL·TB_COL)과 따로 저장된다. 이 화면들은 조회·수집만 하고 물리 모델을 바꾸지 않는다.
 */

export type DbType = 'POSTGRESQL' | 'ORACLE' | 'MYSQL' | 'MARIADB';

export const DB_TYPES: { value: DbType; label: string; defaultPort: number }[] = [
  { value: 'POSTGRESQL', label: 'PostgreSQL', defaultPort: 5432 },
  { value: 'ORACLE', label: 'Oracle', defaultPort: 1521 },
  { value: 'MYSQL', label: 'MySQL', defaultPort: 3306 },
  { value: 'MARIADB', label: 'MariaDB', defaultPort: 3306 },
];

/**
 * 연결 테스트·수집을 할 수 있는 DBMS : 서버에 수집 도구가 있는 것 (lexorma-source 의 PostgresSourceTools 뿐이다).
 * 나머지는 연결 설정만 저장할 수 있다 — 서버도 테스트·수집을 거절한다 (SOURCE_TOOL_NOT_AVAILABLE).
 */
export const COLLECTABLE: ReadonlySet<DbType> = new Set<DbType>(['POSTGRESQL']);

export const dbTypeLabel = (value: string) => DB_TYPES.find((d) => d.value === value)?.label ?? value;

/** 서버의 허용 목록과 같은 연결 옵션 (SourceJdbcSettings.ALLOWED_OPTIONS). 비우면 드라이버 기본값 */
export const OPTIONS: Partial<Record<DbType, { key: string; values: string[] }>> = {
  POSTGRESQL: { key: 'sslmode', values: ['disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full'] },
  MYSQL: { key: 'sslMode', values: ['DISABLED', 'PREFERRED', 'REQUIRED', 'VERIFY_CA', 'VERIFY_IDENTITY'] },
  MARIADB: { key: 'sslMode', values: ['disable', 'trust', 'verify-ca', 'verify-full'] },
};

export type PasswordStatus = 'NONE' | 'CONFIGURED' | 'REREGISTER_REQUIRED' | 'KEY_NOT_CONFIGURED';

export const PASSWORD_STATUS: Record<PasswordStatus, { label: string; variant: LabelVariant }> = {
  NONE: { label: t('비밀번호 없음'), variant: 'attention' },
  CONFIGURED: { label: t('비밀번호 저장됨'), variant: 'success' },
  REREGISTER_REQUIRED: { label: t('비밀번호 재등록 필요'), variant: 'danger' },
  KEY_NOT_CONFIGURED: { label: t('암호화 키 미설정'), variant: 'danger' },
};

export interface SourceConnection {
  id: number;
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  databaseNameType: 'DATABASE' | 'SERVICE_NAME' | 'SID';
  username: string;
  passwordConfigured: boolean;
  passwordStatus: PasswordStatus;
  options: Record<string, string> | null;
  identifierComparison: 'DBMS_DEFAULT' | 'CASE_SENSITIVE' | 'CASE_INSENSITIVE';
  description: string | null;
  enabled: boolean;
  /** 이 배포본에 그 DBMS 의 JDBC 드라이버가 있는지 */
  driverAvailable: boolean;
  schemaCount: number;
}

/** 등록·수정 요청. password 를 비우면(수정) 저장된 비밀번호를 그대로 둔다. JDBC URL 은 보내지 않는다 (서버가 만든다) */
export interface SourceConnectionRequest {
  name: string;
  dbType: DbType;
  host: string;
  port: number | null;
  databaseName: string;
  databaseNameType: string | null;
  username: string;
  password: string | null;
  options: Record<string, string>;
  identifierComparison: string | null;
  description: string | null;
  enabled: boolean;
}

export interface ConnectionTestResult {
  connected: boolean;
  metadataReadable: boolean;
  productName: string | null;
  productVersion: string | null;
  tls: { mode: string | null; encrypted: boolean; protocol: string | null; certificateVerified: boolean; hostnameVerified: boolean } | null;
  errorCode: string | null;
  message: string | null;
}

export type CollectionStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

/** 진행 중 : 상세를 주기적으로 다시 읽는다. COMPLETED 만 원본 카탈로그에 반영된 것이다 */
export const inProgress = (status: string | undefined) => status === 'CREATED' || status === 'RUNNING';

export const COLLECTION_STATUS: Record<CollectionStatus, { label: string; variant: LabelVariant }> = {
  CREATED: { label: t('대기'), variant: 'accent' },
  RUNNING: { label: t('수집 중'), variant: 'accent' },
  COMPLETED: { label: t('완료 · 반영됨'), variant: 'success' },
  FAILED: { label: t('실패 · 반영 안 됨'), variant: 'danger' },
  CANCELLED: { label: t('취소 · 반영 안 됨'), variant: '' },
};

export const TARGET_RESULT: Record<string, { label: string; variant: LabelVariant }> = {
  COMPLETED: { label: t('완료'), variant: 'success' },
  FAILED: { label: t('실패'), variant: 'danger' },
  NOT_FOUND: { label: t('원본에 없음'), variant: 'attention' },
  SKIPPED: { label: t('결과 없음'), variant: '' },
};

export const COLLECTION_TYPE: Record<string, string> = {
  FULL: t('전체'),
  PARTIAL: t('일부 스키마'),
};

export const EXECUTION_TYPE: Record<string, string> = {
  USER: t('사용자'),
  ASYNC: t('수동'),
  SCHEDULED: t('예약'),
};

export interface Execution {
  id: number;
  connectionId: number;
  jobId: string;
  executionType: string;
  collectionType: 'FULL' | 'PARTIAL';
  status: CollectionStatus;
  /** 반영 순서 (COMPLETED 만 있다) : 과거 시점 카탈로그를 고르는 값 */
  reflectedOrder: number | null;
  startedAt: string | null;
  endedAt: string | null;
  reflectedAt: string | null;
  tableCount: number | null;
  columnCount: number | null;
  versionCount: number | null;
  changeCount: number | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface ExecutionTarget {
  pathKey: string;
  catalogName: string | null;
  schemaName: string | null;
  schemaId: number | null;
  result: string;
  tableCount: number | null;
  columnCount: number | null;
  errorCode: string | null;
}

export interface ExecutionDetail {
  execution: Execution;
  targets: ExecutionTarget[];
}

export type ChangeType = 'BASE' | 'ADDED' | 'MODIFIED' | 'REMOVED';

export const CHANGE_TYPE: Record<ChangeType, { label: string; variant: LabelVariant }> = {
  BASE: { label: t('최초 수집'), variant: '' },
  ADDED: { label: t('신규'), variant: 'success' },
  MODIFIED: { label: t('변경'), variant: 'attention' },
  REMOVED: { label: t('삭제'), variant: 'danger' },
};

export const TARGET_TYPE: Record<string, string> = {
  SCHEMA: t('스키마'),
  TABLE: t('테이블·VIEW'),
  COLUMN: t('컬럼'),
  CONSTRAINT: t('제약조건'),
  INDEX: t('인덱스'),
  RELATION: 'FK',
};

export interface SourceChange {
  id: number;
  reflectedOrder: number;
  targetType: string;
  targetKey: string;
  targetName: string;
  schemaId: number | null;
  tableId: number | null;
  creationCycle: number | null;
  changeType: ChangeType;
  changedFields: string[];
  before: unknown;
  after: unknown;
}

export interface PageResult<T> {
  data: T[];
  recordsTotal: number;
}

export interface SchemaState {
  schemaId: number;
  pathKey: string;
  catalogName: string | null;
  schemaName: string;
  creationCycle: number;
  changeType: ChangeType;
  versionOrder: number;
  lastConfirmedOrder: number | null;
}

export interface CatalogAt {
  connectionId: number;
  reflectedOrder: number | null;
  executionId: number | null;
  reflectedAt: string | null;
  schemas: SchemaState[];
}

export interface TableState {
  tableId: number;
  schemaId: number;
  pathKey: string;
  name: string;
  compareName: string;
  type: 'TABLE' | 'VIEW';
  creationCycle: number;
  changeType: ChangeType;
  versionOrder: number;
  lastConfirmedOrder: number | null;
}

export interface TableVersion {
  id: number;
  executionId: number;
  reflectedOrder: number;
  changeType: ChangeType;
  creationCycle: number;
  contentHash: string;
}

// ---------------------------------------------------------------- 원본 카탈로그 (현재)

export interface CatalogSchema {
  id: number;
  connectionId: number;
  catalogName: string | null;
  schemaName: string;
  present: boolean;
  tableCount: number;
}

export interface CatalogTable {
  id: number;
  schemaId: number;
  name: string;
  type: 'TABLE' | 'VIEW';
  quoted: boolean | null;
  comment: string | null;
  present: boolean;
}

interface CatalogColumn {
  id: number;
  name: string;
  quoted: boolean | null;
  ordinal: number;
  dataType: string;
  maxLength: number | null;
  precision: number | null;
  scale: number | null;
  nullable: boolean | null;
  defaultValue: string | null;
  comment: string | null;
  present: boolean;
}

interface CatalogRelation {
  id: number;
  name: string | null;
  tableId: number;
  tableName: string;
  referencedTableId: number | null;
  referencedSchemaName: string | null;
  referencedTableName: string;
  deleteRule: string | null;
  updateRule: string | null;
  columns: { columnId: number; columnName: string; referencedColumnId: number | null; referencedColumnName: string }[];
}

export interface CatalogTableDetail {
  table: CatalogTable;
  schema: CatalogSchema;
  columns: CatalogColumn[];
  constraints: { id: number; name: string | null; type: 'PRIMARY_KEY' | 'UNIQUE' | 'CHECK'; checkExpression: string | null; columns: string[] }[];
  indexes: { id: number; name: string; indexType: string | null; unique: boolean; columns: string[]; members: { columnName: string | null; expression: string | null; sort: string | null }[]; predicate: string | null }[];
  relations: CatalogRelation[];
  referencedBy: CatalogRelation[];
}

// ---------------------------------------------------------------- 화면 표시용 (현재 카탈로그·과거 버전을 같은 모양으로)

export interface ViewColumn {
  name: string;
  ordinal: number | null;
  dataType: string;
  nullable: boolean | null;
  defaultValue: string | null;
  comment: string | null;
}

export interface ViewConstraint {
  name: string | null;
  type: string;
  checkExpression: string | null;
  columns: string[];
}

export interface ViewIndex {
  name: string | null;
  indexType: string | null;
  unique: boolean;
  /** 구성 요소 : 컬럼명 또는 표현식, 정렬 (기본 오름차순이면 null) */
  members: { text: string; expression: boolean; sort: string | null }[];
  predicate: string | null;
}

export interface ViewRelation {
  name: string | null;
  /** 들어오는 FK 에서는 참조하는 쪽 테이블, 나가는 FK 에서는 참조되는 테이블 */
  otherSchema: string | null;
  otherTable: string;
  otherTableId: number | null;
  deleteRule: string | null;
  updateRule: string | null;
  columns: { column: string; referencedColumn: string }[];
}

export interface ViewTable {
  name: string;
  type: string;
  comment: string | null;
  columns: ViewColumn[];
  constraints: ViewConstraint[];
  indexes: ViewIndex[];
  relations: ViewRelation[];
  /** 과거 버전에는 없다 (버전은 테이블 자신의 내용만 담는다) */
  referencedBy: ViewRelation[] | null;
}

export const CONSTRAINT_TYPE: Record<string, string> = {
  PRIMARY_KEY: 'PK',
  UNIQUE: 'UNIQUE',
  CHECK: 'CHECK',
};

/** 데이터 타입 표시 : PostgreSQL 은 format_type 결과(길이·정밀도 포함)를 그대로 받는다 */
export const typeText = (column: { dataType: string }) => column.dataType;

/** 현재 카탈로그의 테이블 상세 → 화면 표시 */
export function fromDetail(detail: CatalogTableDetail): ViewTable {
  const relation = (r: CatalogRelation, incoming: boolean): ViewRelation => ({
    name: r.name,
    otherSchema: incoming ? null : r.referencedSchemaName,
    otherTable: incoming ? r.tableName : r.referencedTableName,
    otherTableId: incoming ? r.tableId : r.referencedTableId,
    deleteRule: r.deleteRule,
    updateRule: r.updateRule,
    columns: r.columns.map((c) => ({ column: c.columnName, referencedColumn: c.referencedColumnName })),
  });
  return {
    name: detail.table.name,
    type: detail.table.type,
    comment: detail.table.comment,
    columns: detail.columns.filter((c) => c.present).map((c) => ({ name: c.name, ordinal: c.ordinal, dataType: c.dataType, nullable: c.nullable, defaultValue: c.defaultValue, comment: c.comment })),
    constraints: detail.constraints.map((c) => ({ name: c.name, type: c.type, checkExpression: c.checkExpression, columns: c.columns })),
    indexes: detail.indexes.map((i) => ({
      name: i.name,
      indexType: i.indexType,
      unique: i.unique,
      members: (i.members?.length ? i.members : i.columns.map((columnName) => ({ columnName, expression: null, sort: null })))
        .map((m) => ({ text: m.columnName ?? m.expression ?? '', expression: m.columnName == null, sort: m.sort })),
      predicate: i.predicate,
    })),
    relations: detail.relations.map((r) => relation(r, false)),
    referencedBy: detail.referencedBy.map((r) => relation(r, true)),
  };
}

interface SnapshotContent {
  name: string;
  type: string;
  comment: string | null;
  columns: { name: string; ordinal: number | null; dataType: string; nullable: boolean | null; defaultValue: string | null; comment: string | null }[];
  constraints: { name: string | null; type: string; checkExpression: string | null; columns: string[] }[];
  indexes: { name: string | null; indexType: string | null; unique: boolean; columns: (string | null)[]; expressions?: (string | null)[]; sorts?: (string | null)[]; predicate?: string | null }[];
  relations: { name: string | null; referencedSchema: string | null; referencedTable: string; deleteRule: string | null; updateRule: string | null; columns: { column: string; referencedColumn: string }[] }[];
}

/**
 * 과거 시점의 테이블 (정규화한 버전 JSON, SourceCatalogCanonical FORMAT 1) → 화면 표시.
 * 하위 객체가 가리키는 컬럼은 비교 이름(PostgreSQL 은 소문자로 접은 이름)으로 저장되어 있어 그대로 보인다.
 */
export function fromSnapshot(content: unknown): ViewTable {
  const c = content as SnapshotContent;
  return {
    name: c.name,
    type: c.type,
    comment: c.comment ?? null,
    columns: (c.columns ?? []).map((col) => ({ name: col.name, ordinal: col.ordinal, dataType: col.dataType, nullable: col.nullable, defaultValue: col.defaultValue, comment: col.comment })),
    constraints: c.constraints ?? [],
    indexes: (c.indexes ?? []).map((i) => ({
      name: i.name,
      indexType: i.indexType,
      unique: i.unique,
      members: (i.columns ?? []).map((column, k) => ({
        text: column ?? i.expressions?.[k] ?? '',
        expression: column == null,
        sort: i.sorts?.[k] ?? null,
      })),
      predicate: i.predicate ?? null,
    })),
    relations: (c.relations ?? []).map((r) => ({
      name: r.name,
      otherSchema: r.referencedSchema,
      otherTable: r.referencedTable,
      otherTableId: null,
      deleteRule: r.deleteRule,
      updateRule: r.updateRule,
      columns: r.columns,
    })),
    referencedBy: null,
  };
}
