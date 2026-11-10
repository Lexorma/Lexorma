import type { GridColumn } from '../../components/grid/Grid';

export const userColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '사용자 ID', column: 'username', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '사용자명', column: 'name', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '권한', column: 'role', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'SELECT' },
  { columnName: '권한 명', column: 'roleName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '비밀번호 초기화', column: 'resetButton', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'BUTTON' },
];

export const projectColumns: GridColumn[] = [
  { columnName: 'id', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'CHECKBOX' },
  { columnName: '프로젝트명', column: 'projectName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '아이디', column: 'username', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '사용자명', column: 'name', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '프로젝트 관리자', column: 'projectManagerYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '활성화여부', column: 'isActive', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT' },
];

export const systemLogColumns: GridColumn[] = [
  { columnName: 'id', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true },
  { columnName: '요청 URL', column: 'logUrl', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '메소드', column: 'method', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: 'IP', column: 'ip', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '사용자 ID', column: 'username', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '응답 HTTP 상태코드', column: 'statusCode', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '요청 발생시간', column: 'timestamp', columnType: 'STRING', isSearch: false, isFilter: true },
  { columnName: '상세보기', column: 'detailButton', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'BUTTON' },
  { columnName: '권한', column: 'roleName', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false},
  { columnName: '요청 데이터', column: 'requestBody', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '응답 데이터', column: 'responseBody', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '에러 메시지', column: 'errorMessage', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '브라우저 정보', column: 'userAgent', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
];
