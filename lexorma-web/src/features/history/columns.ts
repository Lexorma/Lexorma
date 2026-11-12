import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/** 변경 이력 목록. 정렬은 서버가 정한다 (변경 일시·이력 ID 내림차순) → 컬럼 정렬·검색은 쓰지 않고 화면의 조회 조건으로 거른다 */
export const modelHistoryColumns: GridColumn[] = [
  { columnName: t('변경 일시'), column: 'changedAt', columnType: 'STRING', isSearch: false, isFilter: false, width: '14%' },
  { columnName: t('변경 사용자'), column: 'userId', columnType: 'STRING', isSearch: false, isFilter: false, width: '11%' },
  { columnName: t('대상 유형'), column: 'targetType', columnType: 'STRING', isSearch: false, isFilter: false, width: '13%' },
  { columnName: t('변경 대상'), column: 'targetName', columnType: 'STRING', isSearch: false, isFilter: false, width: '22%' },
  { columnName: t('작업'), column: 'action', columnType: 'STRING', isSearch: false, isFilter: false, width: '8%' },
  { columnName: t('변경 항목'), column: 'changedFields', columnType: 'STRING', isSearch: false, isFilter: false, width: '16%' },
  { columnName: t('출처'), column: 'source', columnType: 'STRING', isSearch: false, isFilter: false, width: '8%' },
  { columnName: t('변경 그룹'), column: 'changeGroupId', columnType: 'STRING', isSearch: false, isFilter: false, width: '8%' },
];
