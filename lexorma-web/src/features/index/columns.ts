import type { GridColumn } from '../../components/grid/Grid';
import { t, tc } from '../../lib/i18n';

/**
 * 인덱스 관리 목록 (GET /api/physical/index/data, PhysicalModelDto.IndexRow).
 * 폭(%)은 INDEX_GRID_MIN_WIDTH 기준이다 : 그보다 좁은 화면에서는 줄이지 않고 표 영역을 가로 스크롤한다 (Grid minWidth)
 */
export const INDEX_GRID_MIN_WIDTH = 980;

export const indexColumns: GridColumn[] = [
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('인덱스명'), column: 'indexName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '18%' },
  { columnName: tc('인덱스', '구분'), column: 'isUnique', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '8%' },
  { columnName: t('인덱스 유형'), column: 'indexType', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('컬럼 (순서대로)'), column: 'columnNames', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT', width: '20%' },
  { columnName: t('WHERE 조건'), column: 'condition', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('출처'), column: 'indexSource', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('승인 상태'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('수정 일시'), column: 'updated', columnType: 'DATE', isSearch: false, isFilter: true, rowType: 'TEXT', isVisible: false },
];
