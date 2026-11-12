import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

export const tableColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX', width: 40 },
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '30%' },
  { columnName: t('테이블 한글명'), column: 'tableKorName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '30%' },
  { columnName: t('테이블 설명'), column: 'tableDescription', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', isVisible: false },
  { columnName: t('마스터 테이블'), column: 'isMasterTable', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '12%' },
  { columnName: t('정렬 순서'), column: 'sortOrder', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'TEXT', isVisible: false },
  { columnName: t('승인 여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '13%' },
];
