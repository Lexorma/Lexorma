import { useQuery } from '@tanstack/react-query';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { LabelVariant } from '../../components/grid/cells';

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
}

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
}

export type CheckType = 'MAPPING' | 'TERM' | 'NAME' | 'DATA_TYPE' | 'LENGTH' | 'REQUIRED' | 'IDENTIFIER';
export type CheckStatus = 'MATCH' | 'MISMATCH' | 'NOT_COMPARABLE' | 'UNMAPPED';

/** GET /api/modelMapping/quality/summary : 엔터티·속성 수는 매핑 현황과 같고, 진단 건수는 매핑된 속성 × 진단 유형 */
export interface QualitySummary extends MappingStatus {
  match: number;
  mismatch: number;
  notComparable: number;
  unmapped: number;
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
}

export const CHECK_TYPES: { type: CheckType; label: string }[] = [
  { type: 'MAPPING', label: t('매핑 여부') },
  { type: 'TERM', label: t('표준 용어') },
  { type: 'NAME', label: t('명칭') },
  { type: 'DATA_TYPE', label: t('데이터 타입') },
  { type: 'LENGTH', label: t('길이·정밀도') },
  { type: 'REQUIRED', label: t('필수 여부') },
  { type: 'IDENTIFIER', label: t('식별자') },
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
