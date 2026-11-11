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

/** 매핑 관리 화면 주소 : 모델을 고르고 그 엔터티로 좁혀 연다 */
export const mappingPath = (modelId: number, entityId?: number) => `/modelMapping?modelId=${enc(modelId)}${entityId != null ? `&entityId=${enc(entityId)}` : ''}`;

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
