import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/**
 * VIEW 관리 목록 (GET /api/physical/view/data, ViewDto.Row).
 * 폭(%)은 VIEW_GRID_MIN_WIDTH 기준이다 : 그보다 좁은 화면에서는 줄이지 않고 표 영역을 가로 스크롤한다 (인덱스 관리와 같다)
 */
export const VIEW_GRID_MIN_WIDTH = 960;

export const viewColumns: GridColumn[] = [
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('VIEW명'), column: 'viewName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '19%' },
  { columnName: t('논리명'), column: 'viewKorName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('설명'), column: 'viewDescription', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT', width: '17%' },
  { columnName: t('출력 컬럼'), column: 'columnCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '7%' },
  { columnName: t('참조 객체'), column: 'referenceCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '7%' },
  { columnName: t('승인 상태'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('수정 일시'), column: 'updated', columnType: 'DATE', isSearch: false, isFilter: true, rowType: 'TEXT', width: '15%' },
];
