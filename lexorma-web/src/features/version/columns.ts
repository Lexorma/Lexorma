import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/** 모델 버전 목록. 정렬은 서버가 정한다 (생성 일시 내림차순) → 컬럼 정렬·검색은 쓰지 않는다 */
export const modelVersionColumns: GridColumn[] = [
  { columnName: t('버전명'), column: 'versionName', columnType: 'STRING', isSearch: false, isFilter: false, width: '18%' },
  { columnName: t('설명'), column: 'description', columnType: 'STRING', isSearch: false, isFilter: false, width: '30%' },
  { columnName: t('모델 규모'), column: 'counts', columnType: 'STRING', isSearch: false, isFilter: false, width: '28%' },
  { columnName: t('생성자'), column: 'createdBy', columnType: 'STRING', isSearch: false, isFilter: false, width: '10%' },
  { columnName: t('생성 일시'), column: 'createdAt', columnType: 'STRING', isSearch: false, isFilter: false, width: '14%' },
];
