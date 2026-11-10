import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

export const userColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: t('사용자 ID'), column: 'username', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('사용자명'), column: 'name', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('권한'), column: 'role', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'SELECT' },
  { columnName: t('권한 명'), column: 'roleName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: t('비밀번호 초기화'), column: 'resetButton', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'BUTTON' },
];

export const projectColumns: GridColumn[] = [
  { columnName: 'id', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'CHECKBOX' },
  { columnName: t('프로젝트명'), column: 'projectName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: t('아이디'), column: 'username', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: t('사용자명'), column: 'name', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: t('프로젝트 관리자'), column: 'projectManagerYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: t('활성화여부'), column: 'isActive', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT' },
];

export const systemLogColumns: GridColumn[] = [
  { columnName: 'id', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true },
  { columnName: t('요청 URL'), column: 'logUrl', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('메소드'), column: 'method', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: 'IP', column: 'ip', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('사용자 ID'), column: 'username', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('응답 HTTP 상태코드'), column: 'statusCode', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: t('요청 발생시간'), column: 'timestamp', columnType: 'STRING', isSearch: false, isFilter: true },
  { columnName: t('상세보기'), column: 'detailButton', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'BUTTON' },
  { columnName: t('권한'), column: 'roleName', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false},
  { columnName: t('요청 데이터'), column: 'requestBody', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('응답 데이터'), column: 'responseBody', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('에러 메시지'), column: 'errorMessage', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('브라우저 정보'), column: 'userAgent', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
];
