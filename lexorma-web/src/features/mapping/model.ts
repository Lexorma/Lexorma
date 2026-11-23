import { useQuery } from '@tanstack/react-query';
import { api, enc } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import type { LabelVariant } from '../../components/grid/cells';
import type { Applicability } from './physicalRename';

/** /api/modelMapping (ModelMappingDto·ModelMappingQualityDto) : 논리 엔터티 ↔ 물리 테이블, 논리 속성 ↔ 물리 컬럼 (1:1) */

/** 엔터티 기준 1행. 매핑하지 않은 엔터티는 mappingId·schema·table 이 null */
export interface MappingRow {
  mappingId: number | null;
  modelId: number;
  modelName: string;
  entityId: number;
  entityName: string;
  entityApproval: boolean | null;
  schemaId: number | null;
  schemaName: string | null;
  tableId: number | null;
  tableName: string | null;
  tableApproval: boolean | null;
  attributeCount: number;
  mappedAttributeCount: number;
  /** 상속 역할·상속 PK 현황 (M-7 4차, 목록·상세 조회 때 계산). 일반 엔터티(참여하지 않고 남은 상속 PK 매핑도 없음)는 null */
  inheritance?: MappingInheritance | null;
  /** 물리 매핑 문맥 (M-7 상속 A·C) : 직접 · A형 통합 · C형 분산 · 미매핑 */
  physical?: MappingPhysical | null;
}

/** 엔터티의 물리 매핑 문맥 (ModelMappingDto.Physical). valid false 면 통합·분산이 실제 매핑으로 성립하지 않는다 (미매핑으로 센다) */
export interface MappingPhysical {
  kind: 'DIRECT' | 'MERGED' | 'DISTRIBUTED' | 'UNMAPPED'; strategy: 'SINGLE_TABLE' | 'TABLE_PER_CONCRETE' | null; valid: boolean;
  rootEntityId: number | null; rootEntityName: string | null; tables: { entityId: number; entityName: string | null; mappingId: number | null; schemaId: number | null; tableId: number | null; tableName: string | null }[];
  reason: string | null;
}

export type InheritanceRole = 'ROOT' | 'INTERMEDIATE' | 'SUBTYPE';
export type StructureResult = 'MATCH' | 'MISMATCH' | 'UNMAPPED' | 'NOT_COMPARABLE';

/**
 * 엔터티의 상속 역할과 상속 PK 현황 (ModelMappingDto.Inheritance). 직접 속성 매핑 수와 따로 센다.
 * requiredKeys : 루트 주 식별자 수(서브타입만), mappedKeys : 그중 상속 PK 매핑된 수(≤ requiredKeys), invalidKeys : 유효하지 않은 기존 매핑 수(mappedKeys 에 들어가지 않는다).
 * status : 구조 판정(VALID · 사유 코드 · NOT_SUBTYPE), result : 정합성 비교 상태 — 매핑 없는 엔터티는 null
 */
export interface MappingInheritance {
  role: InheritanceRole | null;
  supertype: boolean;
  subtype: boolean;
  parentEntityId: number | null;
  parentEntityName: string | null;
  rootEntityId: number | null;
  rootEntityName: string | null;
  requiredKeys: number;
  mappedKeys: number;
  invalidKeys: number;
  status: string | null;
  result: StructureResult | null;
}

/** 상속 PK 매핑 1행 (GET /api/modelMapping/entity/{mappingId}/subtypeKeys 의 keys). current false = 지금 루트 주 식별자에 없는 기존 매핑 */
export interface SubtypeKeyRow {
  id: number | null;
  order: number;
  current: boolean;
  attributeId: number;
  attributeName: string;
  attributeEntityId: number;
  attributeEntityName: string;
  columnId: number | null;
  columnName: string | null;
  dataType: string | null;
  maxLength: number | null;
  precision: number | null;
  scale: number | null;
  isPk: boolean | null;
  attributeTermName: string | null;
  attributeDataType: string | null;
  notNull: boolean | null;
  parentColumnName: string | null;
  /** OK · UNMAPPED · OTHER_TABLE · NOT_PK · TYPE_MISMATCH · LENGTH_DIFFERS · STALE */
  check: string | null;
}

export type CheckLevel = 'OK' | 'ERROR' | 'MISSING' | 'UNKNOWN' | 'WARNING';
export interface KeyColumn { columnId: number; columnName: string; dataType: string; pk: boolean; notNull: boolean }
export interface StructureForeignKey { relationId: number; relationName: string | null; source: string | null; targetTableId: number | null; targetTableName: string | null; childColumns: string[]; parentColumns: string[]; parentLink: boolean }
export interface StructureCheck { code: string; level: CheckLevel; message: string }
/** 상속 물리 구조 상세 (상세를 열 때만 계산) — status 와 같은 판정을 항목별로 나눈 것 */
export interface SubtypeStructure {
  result: StructureResult;
  tableId: number; schemaName: string | null; tableName: string;
  parentMappingId: number | null; parentTableId: number | null; parentSchemaName: string | null; parentTableName: string | null;
  tableKey: KeyColumn[]; parentKey: KeyColumn[]; foreignKeys: StructureForeignKey[]; checks: StructureCheck[]; constraintNote: string | null;
}

export interface SubtypeKeyDetail {
  mapping: MappingRow;
  subtype: boolean;
  parentEntityId: number | null;
  parentEntityName: string | null;
  rootEntityId: number | null;
  rootEntityName: string | null;
  status: string;
  statusMessage: string | null;
  complete: boolean;
  keys: SubtypeKeyRow[];
  structure: SubtypeStructure | null;
}

/** 상속 PK 매핑 후보 컬럼 (서버 페이징) */
export interface KeyColumnCandidate {
  columnId: number; columnName: string; columnDesc: string | null; dataType: string | null; maxLength: number | null; precision: number | null; scale: number | null;
  isPk: boolean | null; isNullable: boolean | null; termId: number | null; termName: string | null;
}

export const INHERITANCE_ROLES: Record<InheritanceRole, string> = {
  ROOT: t('루트 슈퍼타입'), INTERMEDIATE: t('중간 슈퍼타입'), SUBTYPE: tc('상속 역할', '서브타입'),
};

/** 구조 정합성 표시 (정합성 비교 상태와 같은 이름) */
export const STRUCTURE_RESULTS: Record<StructureResult, { label: string; variant: LabelVariant }> = {
  MATCH: { label: t('구조 일치'), variant: 'success' },
  MISMATCH: { label: t('구조 불일치'), variant: 'danger' },
  UNMAPPED: { label: t('상속 PK 미완성'), variant: 'attention' },
  NOT_COMPARABLE: { label: t('판단 정보 부족'), variant: '' },
};

/** 상속 구조 판정 사유 (SubtypePlanContext — 서버 코드) */
export const INHERITANCE_STATUS: Record<string, string> = {
  VALID: t('유효'),
  NOT_SUBTYPE: t('서브타입 아님'),
  ROOT_KEY_MISSING: t('루트 주 식별자 없음'),
  ANCESTOR_MISSING: t('상위 엔터티 테이블 없음'),
  ROOT_KEY_MAPPING_MISSING: t('루트 주 식별자 매핑 없음'),
  PARENT_KEY_MISMATCH: t('PK 구성 불일치'),
  INHERITED_KEY_MAPPING_MISSING: t('상속 PK 매핑 미완성'),
  INHERITED_KEY_TYPE_MISMATCH: t('상속 PK 타입 불일치'),
  INHERITANCE_FK_MISSING: t('상속 FK 없음·불일치'),
  ANCESTOR_INVALID: t('상위 상속 경로 불일치'),
};

/** 상세 점검 항목 이름 */
export const STRUCTURE_CHECKS: Record<string, string> = {
  ROOT_KEY_MISSING: t('루트 주 식별자'),
  KEY_COMPLETE: t('상속 PK 매핑 완전성'),
  COLUMN_OWNER: t('컬럼 소속'),
  STALE_MAPPING: t('유효하지 않은 기존 매핑'),
  PK_SET: t('물리 PK 구성'),
  PK_ORDER: t('물리 PK 순서'),
  NOT_NULL: t('NOT NULL'),
  PARENT_TABLE: t('직계 상위 테이블'),
  ANCESTOR_PATH: t('상위 상속 경로'),
  KEY_TYPE: t('데이터 타입'),
  KEY_LENGTH: t('길이·정밀도'),
  PARENT_FK: t('부모 FK'),
};

export const CHECK_LEVELS: Record<CheckLevel, { label: string; variant: LabelVariant }> = {
  OK: { label: t('정상'), variant: 'success' },
  ERROR: { label: t('불일치'), variant: 'danger' },
  MISSING: { label: t('매핑 필요'), variant: 'attention' },
  UNKNOWN: { label: t('판단 불가'), variant: '' },
  WARNING: { label: t('참고'), variant: 'accent' },
};

/** 상속 PK 1개의 점검 (SubtypeKeyRow.check) */
export const KEY_CHECKS: Record<string, { label: string; variant: LabelVariant }> = {
  OK: { label: t('정상'), variant: 'success' },
  UNMAPPED: { label: t('미매핑'), variant: 'attention' },
  OTHER_TABLE: { label: t('다른 테이블 컬럼'), variant: 'danger' },
  NOT_PK: { label: t('PK 아님'), variant: 'danger' },
  TYPE_MISMATCH: { label: t('타입 불일치'), variant: 'danger' },
  LENGTH_DIFFERS: { label: t('길이 다름'), variant: 'accent' },
  STALE: { label: t('유효하지 않은 기존 매핑'), variant: 'danger' },
};

/** 매핑된 엔터티의 속성 1행. 매핑하지 않은 속성은 mappingId·column 이 null */
export interface MappingAttribute {
  mappingId: number | null;
  attributeId: number;
  attributeName: string;
  attributeTermId: number | null;
  attributeTermName: string | null;
  columnId: number | null;
  columnName: string | null;
  columnTermId: number | null;
  columnTermName: string | null;
  /** 속성을 정의한 엔터티 (A형 통합 매핑 — 루트 매핑 아래 서브타입 속성) */
  ownerEntityId?: number | null; ownerEntityName?: string | null;
}

/** 매핑 후보 컬럼 : 매핑된 테이블의 컬럼 중 아직 속성에 연결하지 않은 것 */
export interface ColumnCandidate {
  columnId: number;
  columnName: string;
  columnDesc: string | null;
  termId: number | null;
  termName: string | null;
}

/** GET /api/modelMapping/entity/{mappingId} */
export interface MappingDetail {
  mapping: MappingRow;
  attributes: MappingAttribute[];
  unmappedColumns: ColumnCandidate[];
}

/** 매핑 후보 테이블 : 아직 엔터티에 연결하지 않은 테이블 */
export interface TableCandidate {
  tableId: number;
  schemaId: number;
  schemaName: string;
  tableName: string;
  tableKorName: string | null;
  isApproval: boolean | null;
}

/** GET /api/modelMapping/status?modelId= (배열의 1건) */
export interface MappingStatus {
  modelId: number;
  modelName: string;
  totalEntities: number;
  mappedEntities: number;
  unmappedEntities: number;
  totalAttributes: number;
  mappedAttributes: number;
  unmappedAttributes: number;
  /** 매핑률 구분 (D-AC-16) : 전체 논리 엔터티 · 직접 · 유효한 A형 통합 · 유효한 C형 분산 (분산은 분모에서 뺀다) */
  logicalEntities?: number; directEntities?: number; mergedEntities?: number; distributedEntities?: number;
}

export type CheckType = 'MAPPING' | 'TERM' | 'NAME' | 'DATA_TYPE' | 'LENGTH' | 'REQUIRED' | 'IDENTIFIER' | 'INHERITANCE'
  | 'RELATIONSHIP' | 'ALTERNATE_KEY' | 'PHYSICAL_FK' | 'PHYSICAL_UNIQUE';

/** 진단 범위 (M-7 구조 정합성, 목록 조건 group) : 비우면 예전 목록 (속성·컬럼 + 상속 구조) */
export type CheckGroup = 'ATTRIBUTE' | 'INHERITANCE' | 'RELATIONSHIP' | 'ALTERNATE_KEY' | 'PHYSICAL_ONLY' | 'ALL';

export const CHECK_GROUPS: { group: CheckGroup; label: string; types: CheckType[] }[] = [
  { group: 'ATTRIBUTE', label: t('속성·컬럼'), types: ['MAPPING', 'TERM', 'NAME', 'DATA_TYPE', 'LENGTH', 'REQUIRED', 'IDENTIFIER'] },
  { group: 'RELATIONSHIP', label: t('논리 관계·물리 FK'), types: ['RELATIONSHIP'] },
  { group: 'ALTERNATE_KEY', label: t('보조 식별자·UNIQUE'), types: ['ALTERNATE_KEY'] },
  { group: 'PHYSICAL_ONLY', label: t('물리 전용 구조'), types: ['PHYSICAL_FK', 'PHYSICAL_UNIQUE'] },
  { group: 'INHERITANCE', label: t('상속 관계'), types: ['INHERITANCE'] },
  { group: 'ALL', label: t('전체 진단'), types: [] },
];

/** 구조 진단 상세의 구성 1자리 (ModelMappingQualityDto.StructureColumn) */
export interface StructureColumn {
  order: number;
  logicalAttributeId: number | null; logicalAttributeName: string | null; parentAttributeId: number | null; parentAttributeName: string | null;
  expectedColumnId: number | null; expectedColumnName: string | null; expectedParentColumnId: number | null; expectedParentColumnName: string | null;
  physicalColumnId: number | null; physicalColumnName: string | null; physicalParentColumnId: number | null; physicalParentColumnName: string | null;
}

/** 구조 진단 상세 (ModelMappingQualityDto.Structure) : 관계·보조 식별자·물리 전용 행에만 */
export interface QualityStructure {
  kind: CheckType; code: string; logicalId: number | null; logicalName: string | null; parentEntityId: number | null; parentEntityName: string | null;
  parentTableId: number | null; parentTableName: string | null; physicalId: number | null; physicalName: string | null; columns: StructureColumn[]; candidates: string[];
}

export interface StructureCounts { total: number; match: number; mismatch: number; notComparable: number; unmapped: number }

/** 구조 진단 요약 : 예전 네 건수(속성·컬럼 + 상속 구조)에 들어가지 않는다 */
export interface StructureSummary { relationship: StructureCounts; alternateKey: StructureCounts; physicalOnly: StructureCounts; physicalForeignKeys: number; physicalUniques: number }
export type CheckStatus = 'MATCH' | 'MISMATCH' | 'NOT_COMPARABLE' | 'UNMAPPED';

/** GET /api/modelMapping/quality/summary : 엔터티·속성 수는 매핑 현황과 같고, 진단 건수는 매핑된 속성 × 진단 유형 */
export interface QualitySummary extends MappingStatus {
  match: number;
  mismatch: number;
  notComparable: number;
  unmapped: number;
  /** 슈퍼타입·서브타입 구조에 참여하는 엔터티 수 (M-6 후속 5차, D-48) — 직접 속성 진단은 직접 정의 속성만 본다 */
  inheritanceEntities?: number;
  /** 상속 PK 현황 (M-7 4차) — 직접 속성 매핑률과 따로 센다 */
  inheritance?: InheritanceSummary | null;
  /** 구조 진단 (M-7 구조 정합성) */
  structure?: StructureSummary | null;
}

/** subtypes : 서브타입 수, mappedSubtypes : 그중 매핑된 수, valid·mismatch·unmapped·notComparable 의 합 = mappedSubtypes */
export interface InheritanceSummary {
  subtypes: number; mappedSubtypes: number; requiredKeys: number; mappedKeys: number; unmappedKeys: number; invalidMappings: number;
  valid: number; mismatch: number; unmapped: number; notComparable: number;
}

/** GET /api/modelMapping/quality/data 의 1행. 엔터티 단위 행(매핑하지 않은 엔터티)은 attributeId 가 null */
export interface QualityRow {
  id: string;
  modelId: number;
  modelName: string;
  mappingId: number | null;
  entityId: number;
  entityName: string;
  attributeId: number | null;
  attributeName: string | null;
  schemaId: number | null;
  schemaName: string | null;
  tableId: number | null;
  tableName: string | null;
  columnId: number | null;
  columnName: string | null;
  type: CheckType;
  status: CheckStatus;
  logicalValue: string | null;
  physicalValue: string | null;
  reason: string | null;
  /** 엔터티가 슈퍼타입·서브타입 구조에 참여 : 판정은 직접 정의 속성만 본 것이다 (D-48) */
  inheritance?: boolean;
  /** 구조 진단 상세 (관계·보조 식별자·물리 전용 행에만) */
  structure?: QualityStructure | null;
  /** 상세 코드 (M-7 상속 A·C) */
  code?: string | null;
  /** 구조는 기대와 같아도 DB 가 강제하지 않는 업무 제약 (MATCH 라도 강제된다는 뜻이 아니다) */
  notEnforced?: string[];
}

/** DB 가 강제하지 않는 업무 제약 (QualityRow.notEnforced) */
export const NOT_ENFORCED: Record<string, string> = {
  CONDITIONAL_REQUIRED_NOT_ENFORCED: t('유형 안 필수 미강제'),
  SUBTYPE_SCOPE_NOT_ENFORCED: t('유형별 적용 범위 미강제'),
  GLOBAL_KEY_NOT_ENFORCED: t('테이블 간 유일성 미보장'),
  CLASSIFICATION_VALUE_NOT_ENFORCED: t('분류 값 범위 미강제'),
};

export const CHECK_TYPES: { type: CheckType; label: string }[] = [
  { type: 'MAPPING', label: t('매핑 여부') },
  { type: 'TERM', label: t('표준 용어') },
  { type: 'NAME', label: t('명칭') },
  { type: 'DATA_TYPE', label: t('데이터 타입') },
  { type: 'LENGTH', label: t('길이·정밀도') },
  { type: 'REQUIRED', label: t('필수 여부') },
  { type: 'IDENTIFIER', label: t('식별자') },
  { type: 'INHERITANCE', label: t('상속 구조') },
  { type: 'RELATIONSHIP', label: t('관계·FK') },
  { type: 'ALTERNATE_KEY', label: t('보조 식별자·UNIQUE') },
  { type: 'PHYSICAL_FK', label: t('물리 전용 FK') },
  { type: 'PHYSICAL_UNIQUE', label: t('물리 전용 UNIQUE') },
];

export const CHECK_STATUSES: { status: CheckStatus; label: string; variant: LabelVariant; count: 'match' | 'mismatch' | 'notComparable' | 'unmapped' }[] = [
  { status: 'MATCH', label: t('일치'), variant: 'success', count: 'match' },
  { status: 'MISMATCH', label: t('불일치'), variant: 'danger', count: 'mismatch' },
  { status: 'NOT_COMPARABLE', label: t('비교 불가'), variant: '', count: 'notComparable' },
  { status: 'UNMAPPED', label: t('미매핑'), variant: 'attention', count: 'unmapped' },
];

export const checkTypeLabel = (type: CheckType) => CHECK_TYPES.find((e) => e.type === type)?.label ?? type;

export type MappingTab = 'mapping' | 'quality' | 'recommendation';

/** 매핑 관리 화면 주소 : 모델을 고르고 그 엔터티(ID)로 좁혀 연다. tab·attributeId 는 정합성 비교·물리명 추천 탭으로 바로 갈 때 */
export const mappingPath = (modelId: number, entityId?: number, options: { tab?: MappingTab; attributeId?: number } = {}) =>
  `/modelMapping?modelId=${enc(modelId)}${entityId != null ? `&entityId=${enc(entityId)}` : ''}`
  + `${options.attributeId != null ? `&attributeId=${enc(options.attributeId)}` : ''}${options.tab ? `&tab=${options.tab}` : ''}`;

export type RecommendationStatus = 'AVAILABLE' | 'ALREADY_MATCHED' | 'UNAVAILABLE' | 'CONFLICT';
export type RecommendationSource = 'STANDARD_TERM' | 'STANDARD_WORD';

/** GET /api/modelMapping/recommendation/column/data 의 1행 = 논리 속성 1개. 추천 컬럼명 = 연결된 표준 용어의 영문약어 */
export interface ColumnRecommendation {
  id: string;
  modelId: number;
  modelName: string;
  entityId: number;
  entityName: string;
  mappingId: number | null;
  schemaId: number | null;
  schemaName: string | null;
  tableId: number | null;
  tableName: string | null;
  logicalAttributeId: number;
  logicalName: string;
  termId: number | null;
  termName: string | null;
  termAbbreviation: string | null;
  physicalColumnId: number | null;
  currentPhysicalName: string | null;
  recommendedPhysicalName: string | null;
  status: RecommendationStatus;
  source: RecommendationSource | null;
  reason: string;
  /** 일괄 적용 판정 (M-7 일괄 적용 1차 — 추천끼리 충돌은 전체 후보 기준) */
  applicability: Applicability;
}

/** GET /api/modelMapping/recommendation/column/summary : total = 모델의 속성 수 = 네 상태의 합 */
export interface ColumnRecommendationSummary {
  modelId: number;
  total: number;
  available: number;
  alreadyMatched: number;
  unavailable: number;
  conflict: number;
}

/** 엔터티명을 나눈 단어 1개의 표준 단어 변환 결과 (TableNameDto.Word) */
export interface TableNameWord {
  word: string;
  abbreviation: string | null;
  englishName: string | null;
  status: 'APPROVED' | 'NOT_APPROVED' | 'NOT_FOUND';
}

/** GET /api/modelMapping/recommendation/table/data 의 1행 = 논리 엔터티 1개. 추천 테이블명 = 접두어 + 승인된 표준 단어의 영문약어 */
export interface TableRecommendation {
  id: string;
  modelId: number;
  modelName: string;
  logicalEntityId: number;
  logicalName: string;
  mappingId: number | null;
  schemaId: number | null;
  schemaName: string | null;
  physicalTableId: number | null;
  currentPhysicalName: string | null;
  recommendedPhysicalName: string | null;
  status: RecommendationStatus;
  source: RecommendationSource | null;
  reason: string;
  tablePrefix: string | null;
  words: TableNameWord[];
  /** 일괄 적용 판정 (M-7 일괄 적용 1차) */
  applicability: Applicability;
}

export const RECOMMENDATION_STATUSES: { status: RecommendationStatus; label: string; variant: LabelVariant; count: 'available' | 'alreadyMatched' | 'unavailable' | 'conflict' }[] = [
  { status: 'AVAILABLE', label: t('추천 가능'), variant: 'accent', count: 'available' },
  { status: 'ALREADY_MATCHED', label: t('일치'), variant: 'success', count: 'alreadyMatched' },
  { status: 'UNAVAILABLE', label: t('추천 불가'), variant: '', count: 'unavailable' },
  { status: 'CONFLICT', label: t('충돌'), variant: 'danger', count: 'conflict' },
];

export const RECOMMENDATION_SOURCES: Record<RecommendationSource, string> = {
  STANDARD_TERM: t('표준 용어의 영문약어'),
  STANDARD_WORD: t('표준 단어의 영문약어 + 테이블 접두어'),
};

/** 비율 표시 (전체가 0 이면 계산하지 않는다) */
export const rate = (part: number, total: number) => (total > 0 ? `${((part / total) * 100).toFixed(1)}%` : '-');

/**
 * 엔터티 또는 테이블 하나의 매핑 (논리 모델·테이블 상세의 매핑 표시).
 * 엔터티는 매핑이 없어도 1행(mappingId null), 테이블은 매핑된 경우만 온다 (없으면 undefined).
 */
export function useMappingLookup(target: { entityId?: number; tableId?: number }, enabled = true) {
  const key = target.entityId != null ? `entityId=${enc(target.entityId)}` : `tableId=${enc(target.tableId)}`;
  return useQuery({
    queryKey: ['mapping-lookup', key],
    queryFn: () => api<MappingRow | undefined>(`/api/modelMapping/entity/lookup?${key}`, { silent: true }).then((row) => row ?? null),
    enabled: enabled && (target.entityId != null || target.tableId != null),
    gcTime: 0,
  });
}

/** 물리 컬럼 하나에 대한 컬럼명 추천 (컬럼 상세의 추천 표시). 컬럼이 논리 속성에 매핑되지 않았으면 null */
export function useColumnRecommendation(columnId: number | undefined, enabled = true) {
  return useQuery({
    queryKey: ['mapping-recommendation', 'column', columnId],
    queryFn: () => api<{ data: ColumnRecommendation[] }>(`/api/modelMapping/recommendation/column/data?page=0&size=1&columnId=${enc(columnId)}`, { silent: true })
      .then((page) => page.data[0] ?? null),
    enabled: enabled && columnId != null,
    gcTime: 0,
  });
}
