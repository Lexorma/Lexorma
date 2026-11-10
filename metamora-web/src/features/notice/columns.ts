import type { GridColumn } from '../../components/grid/Grid';

export const noticeColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '게시글 제목', column: 'title', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT' },
  { columnName: '작성자', column: 'username', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '작성일', column: 'updatedTime', columnType: 'DATE', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '확인여부', column: 'isHit', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
];
