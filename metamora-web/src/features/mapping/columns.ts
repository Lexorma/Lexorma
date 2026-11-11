import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/** 엔터티 기준 매핑 목록 (GET /api/modelMapping/entity/data) : 행 1개 = 논리 엔터티 1개. 서버 정렬은 엔터티명·테이블명·스키마만. 컬럼 폭은 고정이다 (조회 결과에 따라 바뀌지 않는다) */
export const mappingEntityColumns: GridColumn[] = [
  { columnName: t('논리 엔터티'), column: 'entityName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '24%' },
  { columnName: t('물리 테이블'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '22%' },
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('매핑 상태'), column: 'mapped', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '12%' },
  { columnName: t('속성 매핑'), column: 'mappedAttributeCount', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('관리'), column: 'actions', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '20%' },
];

/** 정합성 진단 목록 (GET /api/modelMapping/quality/data) : 행 1개 = 매핑된 속성 1개의 진단 유형 1개, 또는 매핑하지 않은 엔터티·속성 1개 */
export const mappingQualityColumns: GridColumn[] = [
  { columnName: t('논리 엔터티'), column: 'entityName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('논리 속성'), column: 'attributeName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '16%' },
  { columnName: t('물리 컬럼'), column: 'columnName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('진단 유형'), column: 'type', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('진단 결과'), column: 'status', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('논리 측 값'), column: 'logicalValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('물리 측 값'), column: 'physicalValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('사유'), column: 'reason', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '15%' },
];

/** 컬럼명 추천 목록 (GET /api/modelMapping/recommendation/column/data) : 행 1개 = 논리 속성 1개. 상태·근거는 서버에서 정렬하지 않는다 */
export const recommendationColumnColumns: GridColumn[] = [
  { columnName: t('논리 엔터티'), column: 'entityName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '13%' },
  { columnName: t('논리 속성'), column: 'logicalName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('표준 용어'), column: 'termName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '13%' },
  { columnName: t('현재 컬럼명'), column: 'currentPhysicalName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '14%' },
  { columnName: t('추천 컬럼명'), column: 'recommendedPhysicalName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '14%' },
  { columnName: t('추천 상태'), column: 'status', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('추천 근거'), column: 'reason', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '21%' },
];

/** 테이블명 추천 목록 (GET /api/modelMapping/recommendation/table/data) : 행 1개 = 논리 엔터티 1개 */
export const recommendationTableColumns: GridColumn[] = [
  { columnName: t('논리 엔터티'), column: 'logicalName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '18%' },
  { columnName: t('현재 테이블명'), column: 'currentPhysicalName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '19%' },
  { columnName: t('추천 테이블명'), column: 'recommendedPhysicalName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '19%' },
  { columnName: t('추천 상태'), column: 'status', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('추천 근거'), column: 'reason', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '33%' },
];
