import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

export const columnColumns: GridColumn[] = [
  { columnName: '', column: 'sortOrder', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX', width: 40 },
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '17%' },
  { columnName: t('컬럼명'), column: 'columnName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('컬럼 설명'), column: 'columnDesc', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT', width: '18%' },
  { columnName: t('데이터 타입'), column: 'dataType', columnType: 'STRING', isSearch: true, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('최대 길이'), column: 'maxLength', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: 'PK', column: 'isPk', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '6%' },
  { columnName: t('정밀도'), column: 'precision', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('소수 자릿수'), column: 'scale', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('기본값'), column: 'defaultValue', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('정렬 순서'), column: 'sortOrder', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('참조 테이블명'), column: 'refTableName', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('데이터 예시'), column: 'example', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('NULL 허용'), column: 'isNullable', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '7%' },
  { columnName: t('마스터 데이터 여부'), column: 'isMasterData', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('필수 입력 여부'), column: 'isRequired', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('민감정보 여부'), column: 'isSensitive', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('고유값 여부'), column: 'isUnique', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('인덱스 생성 여부'), column: 'isIndex', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('암호화 필요 여부'), column: 'isEncrypted', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('승인여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
];
