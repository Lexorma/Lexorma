import type { LabelVariant } from '../../components/grid/cells';
import { t, tc } from '../../lib/i18n';

/**
 * 품질 진단 (AI-6 3차) : 저장된 검사 결과를 보여 주는 화면의 규칙 카탈로그·코드 값. 규칙 코드는 서버 StandardizationRule 과 같고 화면 문구와 따로다 —
 * 코드를 그대로 보여 주지 않고 아래 이름·설명으로 바꿔 보여 준다. 판정은 서버가 하고, 화면은 저장 결과만 읽는다.
 */

export type Severity = 'ERROR' | 'WARNING' | 'INFO';
/** 대상 구분 (규칙 묶음) */
export type RuleGroup = 'MODEL' | 'IDENTIFIER' | 'TERM';
export type ColumnRule = 'TERM_NOT_LINKED' | 'COLUMN_NAME_MISMATCH' | 'DOMAIN_DEFINITION_MISMATCH';
export type RuleCode =
  | ColumnRule | 'TABLE_PREFIX_MISSING' | 'TABLE_WORD_NOT_STANDARD'
  | 'TABLE_COMMENT_MISSING' | 'COLUMN_COMMENT_MISSING' | 'TABLE_BIZ_DESCRIPTION_MISSING' | 'COLUMN_BIZ_DESCRIPTION_MISSING'
  | 'TERM_ABBR_DUPLICATE' | 'TERM_NAME_DUPLICATE' | 'TERM_ABBR_SHARED' | 'TERM_SAME_WORDS' | 'TERM_SYNONYM' | 'TERM_RESTRICTED_WORD'
  | 'IDENTIFIER_LENGTH_EXCEEDED' | 'IDENTIFIER_INVALID_CHARACTER' | 'IDENTIFIER_NOT_RECOMMENDED' | 'CONSTRAINT_NAME_TRUNCATED' | 'CONSTRAINT_NAME_COLLISION';
/** 결과가 가리키는 대상의 종류 (TRGT_TYPE_CD) */
export type TargetType = 'TABLE' | 'COLUMN' | 'SCHEMA' | 'TERM';

export const SEVERITIES: { severity: Severity; label: string; variant: LabelVariant }[] = [
  { severity: 'ERROR', label: t('오류'), variant: 'danger' },
  { severity: 'WARNING', label: t('주의'), variant: 'attention' },
  { severity: 'INFO', label: t('참고'), variant: 'accent' },
];

export const RULE_GROUPS: { group: RuleGroup; label: string }[] = [
  { group: 'MODEL', label: t('모델') },
  { group: 'TERM', label: t('용어') },
  { group: 'IDENTIFIER', label: t('식별자') },
];

export const TARGET_TYPES: { targetType: TargetType; label: string }[] = [
  { targetType: 'TABLE', label: t('테이블') },
  { targetType: 'COLUMN', label: t('컬럼') },
  { targetType: 'SCHEMA', label: t('스키마') },
  { targetType: 'TERM', label: t('표준 용어') },
];

/** 규칙 카탈로그 (서버 정의 20종과 같은 순서) */
export const RULES: { rule: RuleCode; group: RuleGroup; label: string; description: string }[] = [
  { rule: 'TERM_NOT_LINKED', group: 'MODEL', label: t('표준 용어 미연결'), description: t('컬럼이 표준 용어를 참조하지 않습니다.') },
  { rule: 'COLUMN_NAME_MISMATCH', group: 'MODEL', label: t('컬럼명 불일치'), description: t('컬럼명이 연결된 표준 용어의 영문 약어와 다릅니다.') },
  { rule: 'DOMAIN_DEFINITION_MISMATCH', group: 'MODEL', label: t('도메인 정의 불일치'), description: t('컬럼의 타입·길이·정밀도가 표준 도메인 정의와 다릅니다.') },
  { rule: 'TABLE_PREFIX_MISSING', group: 'MODEL', label: t('테이블 접두어 누락'), description: t('테이블명이 프로젝트 접두어로 시작하지 않습니다.') },
  { rule: 'TABLE_WORD_NOT_STANDARD', group: 'MODEL', label: t('비표준 단어 약어'), description: t('테이블명에 승인된 표준 단어 약어가 아닌 약어가 있습니다.') },
  { rule: 'TABLE_COMMENT_MISSING', group: 'MODEL', label: t('테이블 코멘트 누락'), description: t('테이블 코멘트가 비어 있습니다.') },
  { rule: 'COLUMN_COMMENT_MISSING', group: 'MODEL', label: t('컬럼 코멘트 누락'), description: t('컬럼 코멘트(컬럼 설명)가 비어 있습니다.') },
  { rule: 'TABLE_BIZ_DESCRIPTION_MISSING', group: 'MODEL', label: t('테이블 업무 설명 누락'), description: t('테이블 업무 설명이 비어 있습니다.') },
  { rule: 'COLUMN_BIZ_DESCRIPTION_MISSING', group: 'MODEL', label: t('컬럼 업무 설명 누락'), description: t('컬럼 업무 설명이 비어 있습니다.') },
  { rule: 'TERM_ABBR_DUPLICATE', group: 'TERM', label: t('약어·도메인 중복'), description: t('영문 약어와 도메인이 같은 표준 용어가 여럿 있습니다.') },
  { rule: 'TERM_NAME_DUPLICATE', group: 'TERM', label: t('용어명 중복'), description: t('공백·_·대소문자를 무시한 용어명이 같은 표준 용어가 여럿 있습니다.') },
  { rule: 'TERM_ABBR_SHARED', group: 'TERM', label: t('같은 약어 다른 도메인'), description: t('영문 약어는 같고 도메인이 다른 표준 용어가 있습니다.') },
  { rule: 'TERM_SAME_WORDS', group: 'TERM', label: t('같은 단어 구성'), description: t('구성 표준 단어가 같은 표준 용어가 여럿 있습니다(순서 무시).') },
  { rule: 'TERM_SYNONYM', group: 'TERM', label: t('이음동의어 유사'), description: t('이음동의어로 같은 뜻이 되는 표준 용어가 있습니다.') },
  { rule: 'TERM_RESTRICTED_WORD', group: 'TERM', label: t('금칙어 사용'), description: t('다른 표준 단어의 금칙어를 쓰는 표준 용어입니다.') },
  { rule: 'IDENTIFIER_LENGTH_EXCEEDED', group: 'IDENTIFIER', label: t('식별자 길이 초과'), description: t('이름이 프로젝트 DBMS 의 식별자 길이 기준을 넘습니다.') },
  { rule: 'IDENTIFIER_INVALID_CHARACTER', group: 'IDENTIFIER', label: t('DBMS 허용 문자 위반'), description: t('프로젝트 DBMS 가 따옴표 없는 식별자로 받지 않는 문자가 있습니다.') },
  { rule: 'IDENTIFIER_NOT_RECOMMENDED', group: 'IDENTIFIER', label: t('권장 형식 아님'), description: t('DBMS 는 받지만 Lexorma 권장 형식과 다른 이름입니다.') },
  { rule: 'CONSTRAINT_NAME_TRUNCATED', group: 'IDENTIFIER', label: t('제약조건명 잘림'), description: t('DDL 이 만드는 제약조건명이 길이 기준을 넘어 잘립니다.') },
  { rule: 'CONSTRAINT_NAME_COLLISION', group: 'IDENTIFIER', label: t('제약조건명 충돌'), description: t('DDL 의 최종 제약조건·인덱스 이름이 같은 이름 공간에서 겹칩니다.') },
];

export function ruleInfo(rule: string) {
  return RULES.find((r) => r.rule === rule);
}

export function ruleLabel(rule: string): string {
  return ruleInfo(rule)?.label ?? t('알 수 없는 규칙');
}

export function severityInfo(severity: string) {
  return SEVERITIES.find((s) => s.severity === severity);
}

export function targetTypeLabel(targetType: string): string {
  return TARGET_TYPES.find((e) => e.targetType === targetType)?.label ?? targetType;
}

// ---------------------------------------------------------------- 저장 결과 (GET /api/qualityInspection/**)

export type RunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'INTERRUPTED';
export type RuleRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type RunChange = 'NEW' | 'PERSISTING' | 'REOPENED' | 'RULE_SATISFIED' | 'TARGET_DELETED' | 'EXCLUDED';
export type LastChange = 'NEW' | 'PERSISTING' | 'REOPENED' | 'RESOLVED';
export type EndReason = 'RULE_SATISFIED' | 'TARGET_DELETED';

export interface RunRule {
  rule: RuleCode;
  severity: Severity;
  status: RuleRunStatus;
  resultCount: number | null;
  counts: Record<RunChange, number>;
}

export interface Run {
  id: number;
  status: RunStatus;
  requestedAt: string | null;
  startedAt: string | null;
  basisAt: string | null;
  endedAt: string | null;
  errorCode: string | null;
  requestedBy: string | null;
  appliedRuleCount: number;
  partialFailure: boolean;
  failedRules: RuleCode[];
  totals: Record<RunChange, number>;
  rules: RunRule[];
}

export interface RuleSummary {
  rule: RuleCode;
  severity: Severity;
  active: boolean;
  open: number;
  openExcluded: number;
  ruleSatisfied: number;
  targetDeleted: number;
}

export interface Summary {
  lastRun: Run | null;
  activeRun: Run | null;
  latestRun: Run | null;
  open: number;
  bySeverity: Record<Severity, number>;
  openExcluded: number;
  inactiveOpen: number;
  ruleSatisfied: number;
  targetDeleted: number;
  rules: RuleSummary[];
}

export interface Finding {
  id: number;
  rule: RuleCode;
  severity: Severity;
  targetType: TargetType;
  targetId: number;
  tableId: number | null;
  targetName: string;
  status: 'OPEN' | 'RESOLVED';
  excluded: boolean;
  lastChange: LastChange;
  endReason: EndReason | null;
  firstSeenAt: string;
  lastSeenAt: string;
  endedAt: string | null;
  firstRunId: number;
  lastRunId: number;
  version: number;
  ruleActive: boolean;
  actualValue: string | null;
  expectedValue: string | null;
}

export interface FindingHistory {
  id: number;
  type: 'EXCLUDE' | 'UNEXCLUDE' | 'REOPEN';
  status: 'OPEN' | 'RESOLVED';
  excluded: boolean;
  reason: string | null;
  runId: number | null;
  endReason: EndReason | null;
  endedAt: string | null;
  createdAt: string;
  createdBy: string | null;
}

export interface FindingDetail {
  finding: Finding;
  fingerprint: string;
  evidence: {
    fingerprintVersion?: string;
    targetIds?: number[];
    actualValue?: string | null;
    expectedValue?: string | null;
    evidence?: Record<string, unknown>;
    details?: Record<string, unknown>;
  } & Record<string, unknown>;
  history: FindingHistory[];
  targetExists: boolean;
}

export interface RuleSetting {
  rule: RuleCode;
  targetType: string;
  severity: Severity;
  group: RuleGroup;
  active: boolean;
  lastInspectedRunId: number | null;
  lastInspectedAt: string | null;
  recheckNeeded: boolean;
}

export interface RuleSettings {
  rules: RuleSetting[];
  editable: boolean;
}

export const LAST_CHANGES: { change: LastChange; label: string }[] = [
  { change: 'NEW', label: t('신규') },
  { change: 'PERSISTING', label: tc('마지막 변화', '유지') },
  { change: 'REOPENED', label: t('재발') },
  { change: 'RESOLVED', label: t('해결') },
];

export function lastChangeLabel(change: string): string {
  return LAST_CHANGES.find((c) => c.change === change)?.label ?? change;
}

export function endReasonLabel(reason: EndReason | null | undefined): string {
  if (reason === 'TARGET_DELETED') return t('대상 삭제');
  if (reason === 'RULE_SATISFIED') return t('실제 개선');
  return '-';
}

/**
 * 실행 결과의 성격 (AI6-3-08) : 상태값만 보지 않고 오류 코드·적용 규칙 수를 함께 본다.
 * 빈 검사(적용 규칙 0)는 문제가 없다는 뜻이 아니고, 부분 실패는 일부 규칙의 결과가 이전 검사 그대로라는 뜻이다.
 */
export type RunOutcome = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'EMPTY' | 'ALL_RULES_FAILED' | 'STORE_FAILED' | 'BUSY' | 'FAILED' | 'INTERRUPTED';

export function runOutcome(run: Pick<Run, 'status' | 'errorCode' | 'appliedRuleCount'>): RunOutcome {
  if (run.status === 'PENDING' || run.status === 'RUNNING' || run.status === 'INTERRUPTED') return run.status;
  if (run.status === 'COMPLETED') {
    if (run.errorCode === 'RULE_FAILED') return 'PARTIAL';
    return run.appliedRuleCount === 0 ? 'EMPTY' : 'COMPLETED';
  }
  if (run.errorCode === 'ALL_RULES_FAILED') return 'ALL_RULES_FAILED';
  if (run.errorCode === 'STORE_FAILED') return 'STORE_FAILED';
  if (run.errorCode === 'INSPECTION_EXECUTOR_BUSY') return 'BUSY';
  return 'FAILED';
}

export const OUTCOMES: Record<RunOutcome, { label: string; variant: LabelVariant }> = {
  PENDING: { label: t('검사 대기 중'), variant: '' },
  RUNNING: { label: t('검사 진행 중'), variant: 'accent' },
  COMPLETED: { label: t('정상 완료'), variant: 'success' },
  PARTIAL: { label: t('부분 실패'), variant: 'attention' },
  EMPTY: { label: t('빈 검사'), variant: '' },
  ALL_RULES_FAILED: { label: t('전체 규칙 판정 실패'), variant: 'danger' },
  STORE_FAILED: { label: t('결과 저장 실패'), variant: 'danger' },
  BUSY: { label: t('대기열 초과'), variant: 'danger' },
  FAILED: { label: t('실행 실패'), variant: 'danger' },
  INTERRUPTED: { label: t('실행 중단'), variant: 'attention' },
};

/** 실패·중단 사유 안내 (오류 코드를 그대로 보여 주지 않는다) */
export function runErrorText(run: Pick<Run, 'status' | 'errorCode'>): string | null {
  switch (run.errorCode) {
    case null:
    case undefined:
      return null;
    case 'RULE_FAILED': return t('일부 규칙 묶음을 판정하지 못했습니다.');
    case 'ALL_RULES_FAILED': return t('적용한 규칙을 하나도 판정하지 못했습니다.');
    case 'STORE_FAILED': return t('판정 결과를 저장하지 못해 모두 되돌렸습니다.');
    case 'INSPECTION_EXECUTOR_BUSY': return t('검사 요청이 많아 대기열에 넣지 못했습니다.');
    case 'SERVER_RESTARTED': return t('서버가 다시 시작되어 검사가 중단되었습니다.');
    case 'INSPECTION_FAILED': return t('검사 데이터를 읽는 중 오류가 나 판정하지 못했습니다.');
    case 'PROJECT_ACCESS_DENIED':
    case 'PROJECT_NOT_FOUND':
      return t('프로젝트에 접근할 수 없어 결과를 반영하지 않았습니다.');
    case 'EXECUTION_PERMISSION_DENIED':
      return t('요청한 사용자의 권한이 바뀌어 결과를 반영하지 않았습니다.');
    default:
      return t('알 수 없는 오류로 검사가 끝나지 않았습니다.');
  }
}

/** 표준 도메인 이름 중복 ([DICT-2] 예전 데이터 진단 — 같은 프로젝트에서 대소문자를 무시하고 같은 이름) */
export interface DomainDuplicateMember {
  domainId: number;
  domainName: string;
  isApproval: boolean;
  termCount: number;
  columnCount: number;
  registeredAt: string | null;
  modifiedAt: string | null;
}

export interface DomainDuplicateGroup {
  normalizedName: string;
  count: number;
  members: DomainDuplicateMember[];
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
