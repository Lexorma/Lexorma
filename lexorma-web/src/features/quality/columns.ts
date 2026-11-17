import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

/** 열이 많아 좁은 화면에서는 그리드 안에서 가로로 넘긴다 (본문은 넘치지 않는다) */
export const QUALITY_MODEL_GRID_MIN_WIDTH = 1280;
export const QUALITY_TERM_GRID_MIN_WIDTH = 900;

/** 모델 품질 진단 목록 (GET /api/standardizationQuality/model/data) : 행 1개 = 대상(테이블 또는 컬럼) 1개의 규칙 1개 */
export const qualityModelColumns: GridColumn[] = [
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('심각도'), column: 'severity', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '6%' },
  { columnName: t('진단 유형'), column: 'type', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('대상'), column: 'targetType', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '5%' },
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '6%' },
  { columnName: t('컬럼명'), column: 'columnName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '10%' },
  { columnName: t('논리명'), column: 'columnDesc', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '8%' },
  { columnName: t('실제 값'), column: 'actualValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: t('기대 값'), column: 'expectedValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: t('문제 설명'), column: 'message', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '12%' },
  { columnName: t('승인여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '6%' },
  { columnName: t('이동'), column: 'links', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '9%' },
];

/** 표준 용어 진단 목록 (GET /api/standardizationQuality/terms/data) : 행 1개 = 규칙 1개의 용어 그룹. 용어명·약어 검색은 그룹의 용어 중 하나라도 맞으면 */
export const qualityTermColumns: GridColumn[] = [
  { columnName: t('심각도'), column: 'severity', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '7%' },
  { columnName: t('진단 유형'), column: 'type', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '13%' },
  { columnName: t('묶은 값'), column: 'groupKey', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '14%' },
  { columnName: t('용어 수'), column: 'memberCount', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'TEXT', width: '7%' },
  { columnName: t('관련 용어'), column: 'members', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '30%' },
  { columnName: t('판정 근거'), column: 'message', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '29%' },
  { columnName: t('표준용어명'), column: 'termName', columnType: 'STRING', isSearch: true, isFilter: false, isVisible: false },
  { columnName: t('표준용어영문약어명'), column: 'abbreviation', columnType: 'STRING', isSearch: true, isFilter: false, isVisible: false },
];
