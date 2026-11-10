import type { GridColumn } from '../../components/grid/Grid';

export const standardTermsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '제정차수', column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '표준용어명', column: 'commonStandardTermName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준용어설명', column: 'commonStandardTermDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '표준용어영문약어명', column: 'commonStandardTermAbbreviation', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준도메인명', column: 'commonStandardDomainName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '허용값', column: 'allowedValues', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '저장 형식', column: 'storageFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '표현 형식', column: 'displayFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '행정표준코드명', column: 'administrativeStandardCodeName', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '소관기관명', column: 'responsibleOrganization', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '용어 이음동의어 목록', column: 'synonyms', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: '승인여부', column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
];

export const standardWordsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '제정차수', column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '표준단어명', column: 'commonStandardWordName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준단어영문약어명', column: 'commonStandardWordAbbreviation', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준단어 영문명', column: 'commonStandardWordEnglishName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준단어 설명', column: 'commonStandardWordDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '표준도메인분류명', column: 'commonStandardDomainCategory', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '약어사용여부', column: 'useAbbreviation', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: '형식단어 여부', column: 'isFormatWord', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
  { columnName: '이음동의어 목록', column: 'synonyms', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: '금칙어 목록', column: 'restrictedWords', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', isVisible: false },
  { columnName: '승인여부', column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
];

export const standardDomainsColumns: GridColumn[] = [
  { columnName: '', column: 'id', columnType: 'NUMBER', isSearch: false, isFilter: true, rowType: 'CHECKBOX' },
  { columnName: '제정차수', column: 'revisionNumber', columnType: 'NUMBER', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '표준도메인그룹명', column: 'commonStandardDomainGroupName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준도메인분류명', column: 'commonStandardDomainCategory', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준도메인명', column: 'commonStandardDomainName', columnType: 'STRING', isSearch: true, isFilter: true },
  { columnName: '표준도메인설명', column: 'commonStandardDomainDescription', columnType: 'STRING', isSearch: true, isFilter: true, isVisible: false },
  { columnName: '데이터타입', column: 'dataType', columnType: 'STRING', isSearch: false, isFilter: true },
  { columnName: '데이터길이', column: 'dataLength', columnType: 'NUMBER', isSearch: false, isFilter: true },
  { columnName: '데이터소수점길이', column: 'dataDecimalLength', columnType: 'NUMBER', isSearch: false, isFilter: true },
  { columnName: '저장 형식', column: 'storageFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '표현 형식', column: 'displayFormat', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '단위', column: 'unit', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '허용값', column: 'allowedValues', columnType: 'STRING', isSearch: false, isFilter: true, isVisible: false },
  { columnName: '승인여부', column: 'isApprovalYn', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' },
];
