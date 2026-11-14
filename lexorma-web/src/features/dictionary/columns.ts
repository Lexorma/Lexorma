import type { GridColumn } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';

export const standardTermsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX', width: 40 },
  { columnName: t('제정차수'), column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('표준용어명'), column: 'commonStandardTermName', columnType: 'STRING', isSearch: true, isFilter: true, width: '28%' },
  { columnName: t('표준용어설명'), column: 'commonStandardTermDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('표준용어영문약어명'), column: 'commonStandardTermAbbreviation', columnType: 'STRING', isSearch: true, isFilter: true, width: '24%' },
  { columnName: t('표준도메인명'), column: 'commonStandardDomainName', columnType: 'STRING', isSearch: true, isFilter: true, width: '22%' },
  { columnName: t('허용값'), column: 'allowedValues', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('저장 형식'), column: 'storageFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('표현 형식'), column: 'displayFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('행정표준코드명'), column: 'administrativeStandardCodeName', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('소관기관명'), column: 'responsibleOrganization', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('용어 이음동의어 목록'), column: 'synonyms', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  // 사용 건수 (조회 전용) : 누르면 사용 현황을 연다
  { columnName: t('사용 컬럼'), column: 'usageCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '11%' },
  { columnName: t('승인여부'), column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '15%' },
];

export const standardWordsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX', width: 40 },
  { columnName: t('제정차수'), column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('표준단어명'), column: 'commonStandardWordName', columnType: 'STRING', isSearch: true, isFilter: true, width: '20%' },
  { columnName: t('표준단어영문약어명'), column: 'commonStandardWordAbbreviation', columnType: 'STRING', isSearch: true, isFilter: true, width: '18%' },
  { columnName: t('표준단어 영문명'), column: 'commonStandardWordEnglishName', columnType: 'STRING', isSearch: true, isFilter: true, width: '20%' },
  { columnName: t('표준단어 설명'), column: 'commonStandardWordDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('표준도메인분류명'), column: 'commonStandardDomainCategory', columnType: 'STRING', isSearch: true, isFilter: true, width: '15%' },
  { columnName: t('약어사용여부'), column: 'useAbbreviation', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('형식단어 여부'), column: 'isFormatWord', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
  { columnName: t('이음동의어 목록'), column: 'synonyms', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: t('금칙어 목록'), column: 'restrictedWords', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  // 사용 건수 (조회 전용) : 누르면 사용 현황을 연다
  { columnName: t('사용 용어'), column: 'usageCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: t('승인여부'), column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '9%' },
];

export const standardDomainsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX', width: 40 },
  { columnName: t('제정차수'), column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('표준도메인그룹명'), column: 'commonStandardDomainGroupName', columnType: 'STRING', isSearch: true, isFilter: true, width: '15%' },
  { columnName: t('표준도메인분류명'), column: 'commonStandardDomainCategory', columnType: 'STRING', isSearch: true, isFilter: true, width: '15%' },
  { columnName: t('표준도메인명'), column: 'commonStandardDomainName', columnType: 'STRING', isSearch: true, isFilter: true, width: '18%' },
  { columnName: t('표준도메인설명'), column: 'commonStandardDomainDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: t('데이터타입'), column: 'dataType', columnType: 'STRING', isSearch: false, isFilter: true, width: '13%' },
  { columnName: t('데이터길이'), column: 'dataLength', columnType: 'NUMBER', isSearch: false, isFilter: true, width: '9%' },
  { columnName: t('데이터소수점길이'), column: 'dataDecimalLength', columnType: 'NUMBER', isSearch: false, isFilter: true, width: '12%' },
  { columnName: t('저장 형식'), column: 'storageFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('표현 형식'), column: 'displayFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('단위'), column: 'unit', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: t('허용값'), column: 'allowedValues', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  // 사용 건수 (조회 전용) : 누르면 사용 현황을 연다
  { columnName: t('사용 컬럼'), column: 'usageCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '8%' },
  { columnName: t('승인여부'), column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '10%' },
];

/** 사용 현황 : 표준 용어·도메인을 쓰는 컬럼 (GET /api/dictionaryUsage/{term|domain}/{id}/data) */
export const usageColumnColumns: GridColumn[] = [
  { columnName: t('스키마'), column: 'schemaName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '8%' },
  { columnName: t('테이블명'), column: 'tableName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '15%' },
  { columnName: t('테이블 코멘트'), column: 'tableKorName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('컬럼명'), column: 'columnName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('논리명'), column: 'termName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('데이터 타입'), column: 'dataType', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('표준도메인명'), column: 'domainName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '11%' },
  { columnName: t('승인여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '9%' },
  { columnName: t('이동'), column: 'links', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: '15%' },
];

/** 사용 현황 : 표준 단어로 만든 표준 용어 (GET /api/dictionaryUsage/word/{id}/data) */
export const usageTermColumns: GridColumn[] = [
  { columnName: t('표준용어명'), column: 'termName', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '30%' },
  { columnName: t('표준용어영문약어명'), column: 'termAbbreviation', columnType: 'STRING', isSearch: true, isFilter: true, rowType: 'TEXT', width: '26%' },
  { columnName: t('표준도메인명'), column: 'domainName', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '20%' },
  { columnName: t('승인여부'), column: 'isApproval', columnType: 'STRING', isSearch: false, isFilter: true, rowType: 'TEXT', width: '12%' },
  { columnName: t('사용 컬럼'), column: 'columnCount', columnType: 'NUMBER', isSearch: false, isFilter: false, rowType: 'TEXT', width: '12%' },
];

/** 데이터 사전 항목별 화면 경로 (다른 화면에서 검색 조건을 붙여 이동할 때 쓴다) */
export const DICTIONARY_PATHS = { standardDomains: '/standardDomain', standardWords: '/standardWord', standardTerms: '/standardTerm' } as const;
