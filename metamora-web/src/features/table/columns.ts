import type { GridColumn } from '../../components/grid/Grid';

export const tableColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '테이블명', column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT' },
  { columnName: '테이블 한글명', column: 'tableKorName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT' },
  { columnName: '테이블 설명', column: 'tableDescription', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', isVisible: false },
  { columnName: '마스터 테이블', column: 'isMasterTable', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '정렬 순서', column: 'sortOrder', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'TEXT', isVisible: false },
  { columnName: '승인 여부', column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
];
