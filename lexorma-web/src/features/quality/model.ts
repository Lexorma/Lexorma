import type { LabelVariant } from '../../components/grid/cells';
import { t } from '../../lib/i18n';

/** 표준화 규칙 (lexorma-data StandardizationRule) */
export type ColumnRule = 'TERM_NOT_LINKED' | 'COLUMN_NAME_MISMATCH' | 'DOMAIN_DEFINITION_MISMATCH';
export type TableRule = 'TABLE_PREFIX_MISSING' | 'TABLE_WORD_NOT_STANDARD';
export type ModelRule = ColumnRule | TableRule;
export type TermRule = 'TERM_ABBR_DUPLICATE' | 'TERM_NAME_DUPLICATE' | 'TERM_ABBR_SHARED' | 'TERM_SAME_WORDS' | 'TERM_SYNONYM' | 'TERM_RESTRICTED_WORD';
export type Severity = 'ERROR' | 'WARNING' | 'INFO';
export type TargetType = 'TABLE' | 'COLUMN';

export const SEVERITIES: { severity: Severity; label: string; variant: LabelVariant }[] = [
  { severity: 'ERROR', label: t('오류'), variant: 'danger' },
  { severity: 'WARNING', label: t('주의'), variant: 'attention' },
  { severity: 'INFO', label: t('참고'), variant: 'accent' },
];

export const TARGET_TYPES: { targetType: TargetType; label: string }[] = [
  { targetType: 'TABLE', label: t('테이블') },
  { targetType: 'COLUMN', label: t('컬럼') },
];

export const MODEL_RULES: { type: ModelRule; label: string; variant: LabelVariant; target: TargetType }[] = [
  { type: 'TERM_NOT_LINKED', label: t('표준 용어 미연결'), variant: 'attention', target: 'COLUMN' },
  { type: 'COLUMN_NAME_MISMATCH', label: t('컬럼명 불일치'), variant: 'danger', target: 'COLUMN' },
  { type: 'DOMAIN_DEFINITION_MISMATCH', label: t('도메인 정의 불일치'), variant: 'done', target: 'COLUMN' },
  { type: 'TABLE_PREFIX_MISSING', label: t('테이블 접두어 누락'), variant: 'danger', target: 'TABLE' },
  { type: 'TABLE_WORD_NOT_STANDARD', label: t('비표준 단어 약어'), variant: 'danger', target: 'TABLE' },
];

export const TERM_RULES: { type: TermRule; label: string; hint: string }[] = [
  { type: 'TERM_ABBR_DUPLICATE', label: t('약어·도메인 중복'), hint: t('등록 중복 기준 · 대소문자 무시') },
  { type: 'TERM_NAME_DUPLICATE', label: t('용어명 중복'), hint: t('공백·_·대소문자 무시') },
  { type: 'TERM_ABBR_SHARED', label: t('같은 약어 다른 도메인'), hint: t('컬럼명·업로드에서 모호함') },
  { type: 'TERM_SAME_WORDS', label: t('같은 단어 구성'), hint: t('단어 순서 무시') },
  { type: 'TERM_SYNONYM', label: t('이음동의어 유사'), hint: t('용어·단어 이음동의어') },
  { type: 'TERM_RESTRICTED_WORD', label: t('금칙어 사용'), hint: t('다른 단어의 금칙어') },
];

export interface ColumnSummary {
  totalColumns: number;
  issueColumns: number;
  termNotLinked: number;
  columnNameMismatch: number;
  domainDefinitionMismatch: number;
}

/** GET /api/standardizationQuality/model/summary (columns = 기존 요약과 같은 값) */
export interface ModelSummary {
  columns: ColumnSummary;
  totalTables: number;
  issueTables: number;
  tablePrefixMissing: number;
  tableWordNotStandard: number;
  tablePrefix: string | null;
  errors: number;
  warnings: number;
  infos: number;
}

/** GET /api/standardizationQuality/terms/summary */
export interface TermSummary {
  totalTerms: number;
  issueTerms: number;
  groups: Partial<Record<TermRule, number>>;
  errors: number;
  warnings: number;
  infos: number;
}

export interface TermMember {
  termId: number;
  termName: string | null;
  abbreviation: string | null;
  domainName: string | null;
  isApproval: boolean | null;
  columnCount: number | null;
  note: string | null;
}

export interface TermGroup {
  id: string;
  type: TermRule;
  severity: Severity;
  groupKey: string | null;
  memberCount: number;
  members: TermMember[];
  message: string;
  evidence: Record<string, string>;
}

// ---------------------------------------------------------------- 컬럼명 개선 후보

export type SuggestionSource = 'LINKED_TERM' | 'ABBR_EXACT' | 'NAME_EXACT' | 'SYNONYM' | 'WORD_COMPOSE' | 'SAME_DESC_COLUMN' | 'UNREGISTERED';
export type SuggestionAction = 'LINK_TERM' | 'LINK_TERM_AND_RENAME' | 'RENAME' | 'REGISTER_TERM';
export type DomainFit = 'MATCH' | 'MISMATCH' | 'NO_DOMAIN';

export const SUGGESTION_SOURCES: Record<SuggestionSource, string> = {
  LINKED_TERM: t('연결된 용어의 약어'),
  ABBR_EXACT: t('약어 = 컬럼명'),
  NAME_EXACT: t('용어명 = 논리명'),
  SYNONYM: t('이음동의어 = 논리명'),
  WORD_COMPOSE: t('표준 단어 조합'),
  SAME_DESC_COLUMN: t('같은 논리명 컬럼'),
  UNREGISTERED: t('미등록 조합'),
};

export const SUGGESTION_ACTIONS: Record<SuggestionAction, string> = {
  LINK_TERM: t('용어 연결'),
  LINK_TERM_AND_RENAME: t('용어 연결 + 컬럼명 변경'),
  RENAME: t('컬럼명 변경'),
  REGISTER_TERM: t('새 용어 등록 검토'),
};

export interface SuggestionCandidate {
  source: SuggestionSource;
  sources: SuggestionSource[];
  score: number;
  action: SuggestionAction;
  termId: number | null;
  termName: string | null;
  termAbbreviation: string | null;
  termApproved: boolean | null;
  recommendedColumnName: string | null;
  proposedTermName: string | null;
  words: { abbreviation: string; wordName: string; approved: boolean }[];
  domainName: string | null;
  domainFit: DomainFit;
  attributes: ('DATA_TYPE' | 'LENGTH' | 'PRECISION' | 'SCALE')[];
  actualType: string | null;
  expectedType: string | null;
  nameConflict: boolean;
  conflictColumns: string[];
  candidateConflict: boolean;
  candidateConflictColumns: string[];
  sameDescColumns: number | null;
  reason: string | null;
}

/** GET /api/standardizationQuality/columns/{columnId}/suggestions */
export interface ColumnSuggestions {
  columnId: number;
  columnName: string | null;
  columnDesc: string | null;
  dataType: string | null;
  tableId: number;
  tableName: string | null;
  schemaId: number;
  termId: number | null;
  termName: string | null;
  termAbbreviation: string | null;
  issueTypes: ColumnRule[];
  candidates: SuggestionCandidate[];
  truncated: boolean;
  reason: string | null;
}

export const ATTRIBUTE_LABELS: Record<SuggestionCandidate['attributes'][number], string> = {
  DATA_TYPE: t('데이터 타입'),
  LENGTH: t('길이'),
  PRECISION: t('정밀도'),
  SCALE: t('소수 자릿수'),
};
