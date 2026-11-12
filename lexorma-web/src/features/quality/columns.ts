import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/** 표준화 품질 진단 목록 (GET /api/standardizationQuality/data) : 행 1개 = 컬럼 1개의 진단 유형 1개 */
export const qualityIssueColumns: GridColumn[] = [
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('진단 유형'), column: 'type', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '7%' },
  { columnName: t('컬럼명'), column: 'columnName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('논리명'), column: 'columnDesc', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('실제 값'), column: 'actualValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '9%' },
  { columnName: t('기대 값'), column: 'expectedValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '9%' },
  { columnName: t('문제 설명'), column: 'message', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('승인여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '8%' },
  { columnName: t('이동'), column: 'links', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '12%' },
];
