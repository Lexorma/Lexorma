import { t } from '../../lib/i18n';

/** 모델 변경 이력 (M-9) : 서버 ModelChangeHistoryDto 와 같은 모양 */
export interface HistorySummary {
  id: number;
  projectId: number;
  changeGroupId: string;
  targetType: string;
  targetId: number;
  targetName: string | null;
  action: string;
  source: string;
  userId: string | null;
  changedAt: string;
  changedFields: string[];
}

export type HistoryState = Record<string, unknown>;

export interface HistoryDetail {
  summary: HistorySummary;
  before: HistoryState | null;
  after: HistoryState | null;
}

/** 대상 종류 (서버 ModelChangeHistory.TargetType 과 같은 값, 화면에 나열하는 순서) */
export const TARGET_TYPES: [string, string][] = [
  ['PHYSICAL_TABLE', t('물리 테이블')],
  ['PHYSICAL_COLUMN', t('물리 컬럼')],
  ['PHYSICAL_RELATION', t('물리 관계')],
  ['PHYSICAL_INDEX', t('인덱스')],
  ['PHYSICAL_CONSTRAINT', t('제약조건')],
  ['PHYSICAL_VIEW', 'VIEW'],
  ['LOGICAL_MODEL', t('논리 모델')],
  ['LOGICAL_ENTITY', t('논리 엔터티')],
  ['LOGICAL_ATTRIBUTE', t('논리 속성')],
  ['LOGICAL_IDENTIFIER', t('논리 식별자')],
  ['LOGICAL_RELATIONSHIP', t('논리 관계')],
  ['TABLE_MAPPING', t('엔터티 ↔ 테이블 매핑')],
  ['COLUMN_MAPPING', t('속성 ↔ 컬럼 매핑')],
];

export const ACTIONS: [string, string][] = [
  ['CREATE', t('생성')],
  ['UPDATE', t('수정')],
  ['DELETE', t('삭제')],
  ['APPROVE', t('승인')],
  ['REVOKE', t('승인취소')],
];

const SOURCES: Record<string, string> = { USER: t('사용자'), AUTO_SYNC: t('자동 동기화') };

/** 모르는 코드는 그대로 보여 준다 (나중에 대상·작업이 늘어도 화면이 깨지지 않는다) */
export const targetTypeLabel = (code: string) => TARGET_TYPES.find(([value]) => value === code)?.[1] ?? code;
export const actionLabel = (code: string) => ACTIONS.find(([value]) => value === code)?.[1] ?? code;
export const sourceLabel = (code: string) => SOURCES[code] ?? code;

export const ACTION_VARIANT: Record<string, 'accent' | 'success' | 'attention' | 'danger' | 'done' | ''> = {
  CREATE: 'success', UPDATE: 'accent', DELETE: 'danger', APPROVE: 'done', REVOKE: 'attention',
};

/** 이력 JSON 의 필드 이름 → 화면 이름. 여기 없는 필드(예전·나중의 필드)는 원본 키로 보여 준다 */
const FIELD_LABELS: Record<string, string> = {
  tableName: t('테이블명'), tableKorName: t('테이블 한글명'), tableDescription: t('테이블 설명'), isMasterTable: t('마스터 테이블'), sortOrder: t('정렬 순서'),
  isApproval: t('승인 여부'), schema: t('스키마'), tableInfo: t('테이블'), columnName: t('컬럼명'), columnDesc: t('컬럼 설명'), dataType: t('데이터 타입'),
  maxLength: t('최대 길이'), precision: t('정밀도'), scale: t('소수 자릿수'), isNullable: t('NULL 허용'), defaultValue: t('기본값'), isMasterData: t('마스터 데이터'),
  refTableName: t('참조 테이블명'), isRequired: t('필수'), isSensitive: t('민감 정보'), isPk: 'PK', isUnique: t('고유값'), isIndex: t('인덱스'), isEncrypted: t('암호화'),
  example: t('데이터 예시'), standardTerms: t('표준 용어'), standardTerm: t('표준 용어'), sourceTable: t('원본 테이블'), targetTable: t('대상 테이블'), relationName: t('관계명'),
  relationType: t('관계 유형'), relationSource: t('관계 출처'), derivedColumnId: t('참조 테이블명을 지정한 컬럼 ID'), columns: t('컬럼 구성'), indexName: t('인덱스명'),
  indexType: t('인덱스 방식'), constraintName: t('제약조건명'), constraintType: t('제약조건 유형'), checkExpression: t('CHECK 조건식'),
  indexSource: t('인덱스 출처'), condition: t('WHERE 조건'), expression: t('표현식'), sort: t('정렬'),
  viewName: t('VIEW명'), viewKorName: t('논리명'), viewDescription: t('설명'), definition: t('정의 SQL'), references: t('참조 객체'),
  columnKorName: t('컬럼 논리명'), columnDescription: t('컬럼 설명'), referenceType: t('참조 유형'), referencedTable: t('참조 테이블'), referencedView: t('참조 VIEW'),
  objectName: t('객체명'),
  modelName: t('모델명'), modelDescription: t('모델 설명'), model: t('논리 모델'), entityName: t('엔터티명'), entityDescription: t('엔터티 설명'), entity: t('엔터티'),
  attributeName: t('속성명'), attributeDescription: t('속성 설명'), identifierName: t('식별자명'), isPrimary: t('주 식별자'), attributes: t('구성 속성'),
  sourceEntity: t('출발 엔터티'), targetEntity: t('도착 엔터티'), relationshipName: t('관계명'), relationshipDescription: t('관계 설명'), isIdentifying: t('식별 관계'),
  sourceMin: t('출발 최소'), sourceMax: t('출발 최대'), targetMin: t('도착 최소'), targetMax: t('도착 최대'),
  schemaName: t('스키마명'), schemaDescription: t('설명'), isDefault: t('기본 스키마'),
  table: t('테이블'), mapping: t('엔터티 매핑'), attribute: t('속성'), column: t('컬럼'), sourceColumn: t('원본 컬럼'), targetColumn: t('대상 컬럼'), columnOrder: t('순서'),
};

export const fieldLabel = (key: string) => FIELD_LABELS[key] ?? key;

/** 비교 표의 한 줄 */
export interface DiffRow {
  key: string;
  label: string;
  before: string[];
  after: string[];
  changed: boolean;
}

const isRecord = (value: unknown): value is HistoryState => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * 연관 대상은 "<필드>Id" 와 "<필드>Name"(그 시점의 이름)으로 저장된다 → 한 항목 "<필드>" 로 합친다.
 * 이름이 없는 예전 이력은 ID 만 보여 준다 (지금의 이름을 찾아 넣지 않는다).
 */
function fields(state: HistoryState): Map<string, unknown> {
  const result = new Map<string, unknown>();
  for (const [key, value] of Object.entries(state)) {
    if (key.endsWith('Id') && key.length > 2 && `${key.slice(0, -2)}Name` in state) {
      result.set(key.slice(0, -2), { id: value, name: state[`${key.slice(0, -2)}Name`] });
    } else if (key.endsWith('Name') && `${key.slice(0, -4)}Id` in state) {
      // 위에서 합쳤다
    } else if (key.endsWith('Id') && !(key in FIELD_LABELS) && key.slice(0, -2) in FIELD_LABELS) {
      // 이름 스냅샷이 없던 때의 이력 (M-9 1차) : 같은 항목 이름 아래에 ID 만 보여 준다
      result.set(key.slice(0, -2), { id: value, name: null });
    } else {
      result.set(key, value);
    }
  }
  return result;
}

function text(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'boolean') return value ? 'Y' : 'N';
  if (isRecord(value)) {
    // 합친 연관 대상 { id, name }
    if ('id' in value && 'name' in value && Object.keys(value).length === 2) {
      if (value.id === null || value.id === undefined) return '-';
      return value.name ? `${String(value.name)} (#${String(value.id)})` : `#${String(value.id)}`;
    }
    return [...fields(value)].map(([key, item]) => `${fieldLabel(key)} ${text(item)}`).join(', ');
  }
  return String(value);
}

/** 배열(컬럼 구성·구성 속성)은 한 줄에 한 항목. 순서 필드는 줄 번호로 대신한다 */
function lines(value: unknown): string[] {
  if (!Array.isArray(value)) return [text(value)];
  if (value.length === 0) return ['-'];
  return value.map((item, index) => {
    if (!isRecord(item)) return text(item);
    const parts = [...fields(item)].filter(([key]) => key !== 'columnOrder' && key !== 'sortOrder');
    // 관계의 컬럼 쌍은 "원본 → 대상", 그 밖에는 값만 나열한다
    const body = parts.length === 2 && parts[0][0] === 'sourceColumn' ? `${text(parts[0][1])} → ${text(parts[1][1])}` : parts.map(([, part]) => text(part)).join(', ');
    return `${index + 1}. ${body}`;
  });
}

/** 순서에 뜻이 없는 구성 : UNIQUE·CHECK 제약조건의 컬럼, VIEW 의 참조 객체 (서버도 집합으로 비교한다). 관계·인덱스·식별자·VIEW 출력 컬럼은 순서가 뜻이 있다 */
const unordered = (targetType: string, key: string) => targetType === 'PHYSICAL_CONSTRAINT' && key === 'columns' || targetType === 'PHYSICAL_VIEW' && key === 'references';

function comparable(value: unknown, ignoreOrder: boolean): string {
  if (!ignoreOrder || !Array.isArray(value)) return JSON.stringify(value ?? null);
  return JSON.stringify(value.map((item) => (isRecord(item) ? JSON.stringify({ ...item, columnOrder: undefined, sortOrder: undefined }) : JSON.stringify(item))).sort());
}

/**
 * 변경 전후 JSON → 비교 줄. 저장된 키를 그대로 쓴다 : 한쪽에만 있는 필드, 모르는 필드, null, 빈 배열, 중첩 객체 모두 줄로 나온다.
 * changed 는 값이 실제로 다른 줄이다 (생성·삭제는 모든 줄이 한쪽뿐이다).
 */
export function diffRows(detail: { summary: { targetType: string }; before: HistoryState | null; after: HistoryState | null }): DiffRow[] {
  const before = detail.before ? fields(detail.before) : new Map<string, unknown>();
  const after = detail.after ? fields(detail.after) : new Map<string, unknown>();
  const keys = [...new Set([...after.keys(), ...before.keys()])];
  return keys.map((key) => {
    const ignoreOrder = unordered(detail.summary.targetType, key);
    return {
      key,
      label: fieldLabel(key),
      before: detail.before ? (before.has(key) ? lines(before.get(key)) : ['']) : [],
      after: detail.after ? (after.has(key) ? lines(after.get(key)) : ['']) : [],
      changed: !detail.before || !detail.after || before.has(key) !== after.has(key)
        || comparable(before.get(key), ignoreOrder) !== comparable(after.get(key), ignoreOrder),
    };
  });
}

/** 목록의 "변경 항목" : 서버가 준 키 이름을 화면 이름으로 (연관 대상의 Id·Name 은 한 번만) */
export function changedLabels(changedFields: string[]): string[] {
  const names = changedFields.map((key) => (key.endsWith('Id') && !(key in FIELD_LABELS) && key.slice(0, -2) in FIELD_LABELS ? key.slice(0, -2)
    : key.endsWith('Name') && changedFields.includes(`${key.slice(0, -4)}Id`) ? key.slice(0, -4) : key));
  return [...new Set(names)].map(fieldLabel);
}

/** 변경 이력 화면 주소 (다른 화면에서 객체별 이력으로 넘어올 때) */
export const historyPath = (targetType: string, targetId: number | string) => `/modelHistory?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}`;
