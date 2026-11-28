import type { GridColumn } from '../../components/grid/Grid';
import { t, tc } from '../../lib/i18n';

/** 열이 많아 좁은 화면에서는 그리드 안에서 가로로 넘긴다 (본문은 넘치지 않는다) */
export const QUALITY_FINDING_GRID_MIN_WIDTH = 1180;
export const QUALITY_RUN_GRID_MIN_WIDTH = 1280;

/**
 * 품질 진단 결과 목록 (GET /api/qualityInspection/findings/data) : 행 1개 = 저장된 결과(규칙 × 대상) 1건. 서버가 필터·정렬·페이징한다.
 * 정렬할 수 있는 열 = 서버 허용 목록(InspectionQueryRepository.SORTS) — isFilter: false 는 정렬하지 않는 열이다.
 */
export const qualityFindingColumns: GridColumn[] = [
  { columnName: t('심각도'), column: 'severity', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '7%' },
  { columnName: t('진단 규칙'), column: 'rule', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '13%' },
  { columnName: t('대상 유형'), column: 'targetType', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '7%' },
  { columnName: t('대상'), column: 'targetName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '20%' },
  { columnName: t('진단 내용'), column: 'message', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '22%' },
  { columnName: t('실제 문제 상태'), column: 'status', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('예외'), column: 'excluded', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('마지막 변화'), column: 'lastChange', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '7%' },
  { columnName: t('최종 확인'), column: 'lastSeenAt', columnType: 'DATE', isSearch: false, isFilter: true, rowType: 'TEXT', width: '8%' },
];

/** 실행 이력 (GET /api/qualityInspection/runs/data) : 정렬은 요청 일시 하나 (요청 순서). 건수는 그 실행이 결과를 확정한 시점의 값이다 */
export const qualityRunColumns: GridColumn[] = [
  { columnName: t('요청 일시'), column: 'requestedAt', columnType: 'DATE', isSearch: false, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('실행 결과'), column: 'status', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('요청자'), column: 'requestedBy', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: t('검사 기준 시각'), column: 'basisAt', columnType: 'DATE', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('완료 시각'), column: 'endedAt', columnType: 'DATE', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('소요 시간'), column: 'duration', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '7%' },
  { columnName: t('적용 규칙'), column: 'appliedRuleCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('실패 규칙'), column: 'failedRules', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('신규'), column: 'NEW', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: tc('마지막 변화', '유지'), column: 'PERSISTING', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('해결'), column: 'resolved', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('재발'), column: 'REOPENED', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('예외'), column: 'EXCLUDED', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
];
