import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import type { Schema } from '../schema/schemas';
import type { PairState } from '../logical/model';
import { PAIR_WARNINGS, PairList, PairStatusLabel } from '../logical/relationshipPairs';
import type { MappingRow } from './model';
import { InheritanceRoleLabel } from './subtypeKeys';

/** 한 요청에서 고를 수 있는 엔터티 수 (서버 제한과 같다 — 넘으면 400) */
export const GENERATION_MAX_ENTITIES = 100;
const ENTITY_PAGE_SIZE = 20;
interface ColumnPlan { attributeId: number; attributeName: string; columnName: string; dataType: string; maxLength: number | null; precision: number | null; scale: number | null; pk: boolean; nullable: boolean; refTableName: string | null;
  /** 상속 PK 컬럼이면 루트 엔터티 (M-7 2차) */
  inheritedFromEntityId?: number | null }
interface AttributeChoice { attributeId: number; attributeName: string; primary: boolean; excluded: boolean; excludeRequested: boolean; foreignKey: boolean }
interface TablePlan {
  entityId: number; entityName: string; status: 'CREATE' | 'SKIP' | 'ERROR' | 'UNSUPPORTED'; tableName: string | null; schemaName: string; columns: ColumnPlan[]; errors: string[]; warnings: string[];
  attributes: AttributeChoice[];
  /** 슈퍼타입·서브타입 B형 계획 (M-7 2차, 참여하지 않으면 null) */
  inheritance?: InheritancePlan | null;
}
type InheritanceRole = 'ROOT' | 'INTERMEDIATE' | 'SUBTYPE';
interface InheritancePlan { strategy: string; role: InheritanceRole; level: number; parentEntityId: number | null; parentEntityName: string | null; rootEntityId: number; rootEntityName: string; groupId: number | null; groupName: string | null; reasons: string[] }
interface InheritanceForeignKeyPlan {
  childEntityId: number; childEntityName: string; parentEntityId: number; parentEntityName: string; groupId: number; status: 'READY' | 'BLOCKED'; reason: string | null;
  fkType: 'SINGLE' | 'COMPOSITE' | null; parentTableName: string | null; parentTableId: number | null; childTableName: string | null; constraintName: string | null; columns: FkColumn[];
}
interface RequiredAncestor { entityId: number; entityName: string; reason: string; requiredBy: number[] }
/** 조상 하나의 쓰임 (M-7 4차) : SELECTED 이번에 만든다 · REUSE 유효한 기존 테이블 재사용 · REVIEW 기존 매핑 구조가 맞지 않음(함께 선택해도 해결 안 됨) · MISSING 선택 필요 */
interface AncestorUse { entityId: number; entityName: string; state: 'SELECTED' | 'REUSE' | 'REVIEW' | 'MISSING'; reason: string | null; mappingId: number | null; tableName: string | null; requiredBy: number[] }
interface ConstraintWarning { groupId: number; groupName: string; supertypeEntityId: number; supertypeEntityName: string; constraint: 'DISJOINT' | 'TOTAL'; guarantee: string; message: string }
const INHERITANCE_ROLES: Record<InheritanceRole, string> = { ROOT: t('루트 슈퍼타입'), INTERMEDIATE: t('중간 슈퍼타입'), SUBTYPE: tc('상속 역할', '서브타입') };
export interface GenerationResult {
  tables: number; columns: number; tableIds: number[]; versionId: number | null; versionName: string | null; foreignKeys: number;
  /** M-7 3차 : 만든 상속 FK 수, 저장한 상속 PK 매핑 수 */
  inheritanceForeignKeys?: number; inheritedKeyMappings?: number;
  /** M7-AK-01 : 만든 보조 식별자 UNIQUE 수 */
  uniques?: number;
}
type IdentifierCode = 'IDENTIFIER_LENGTH_EXCEEDED' | 'IDENTIFIER_INVALID_CHARACTER' | 'IDENTIFIER_NOT_RECOMMENDED' | 'CONSTRAINT_NAME_TRUNCATED' | 'CONSTRAINT_NAME_COLLISION';
interface IdentifierIssue {
  entityId: number | null; attributeId: number | null; code: IdentifierCode; severity: 'ERROR' | 'WARNING'; kind: string; name: string; ddlName: string | null;
  length: number | null; maxLength: number | null; basis: string; message: string; guide: string; related: string[];
}
type RelationReason = 'ATTRIBUTE_PAIRS_MISSING' | 'ATTRIBUTE_PAIRS_INCOMPLETE' | 'ATTRIBUTE_PAIRS_INVALID' | 'COLUMN_UNAVAILABLE' | 'PARENT_KEY_NOT_TABLE_KEY' | 'SELF_KEY_ONLY' | 'TYPE_INCOMPATIBLE' | 'CHILD_COLUMN_IN_USE' | 'SELF_REFERENCE' | 'MULTIPLE_RELATIONSHIPS' | 'COMPOSITE_PARENT_KEY' | 'PARENT_KEY_MISSING' | 'PARENT_TABLE_MISSING' | 'INHERITED_KEY_MAPPING_MISSING'
  | 'UNIQUE_ORDER_DIFFERS' | 'PARENT_UNIQUE_MISSING';
interface RelationPlan {
  relationshipId: number; relationshipName: string | null; parentEntityId: number; parentEntityName: string; childEntityId: number; childEntityName: string;
  fkCreated: boolean; reasons: RelationReason[]; message: string; approved: boolean | null;
  /** 논리 관계 속성 짝 (M-7 보강 3차) */
  pairStatus: PairState['status']; pairWarnings: PairState['warnings']; pairs: PairState['pairs'];
  /** FK 생성 계획 (M-7 보강 4차) */
  fkStatus: FkStatus; fkType: FkType | null; parentTableName: string | null; parentTableId: number | null; childTableName: string | null;
  constraintName: string | null; refTableName: string | null; columns: FkColumn[];
  /** 짝이 가리키는 부모 식별자 (M7-AK-03) : PRIMARY · ALTERNATE (보조 식별자 UNIQUE 참조 — 단일도 미승인 사용자 관계) */
  keyType?: 'PRIMARY' | 'ALTERNATE' | null; keyIdentifierName?: string | null;
}
/** 보조 식별자 → UNIQUE 계획 (M7-AK-01) : CREATE 만 만든다. SKIP 사유 PRIMARY_KEY_DUPLICATE · DUPLICATE_ALTERNATE_KEY · COLUMN_UNAVAILABLE, 경고 NULLABLE_COLUMNS */
interface UniquePlan {
  entityId: number; entityName: string; identifierId: number; identifierName: string; tableName: string | null; constraintName: string | null; columns: string[];
  status: 'CREATE' | 'SKIP'; reason: 'PRIMARY_KEY_DUPLICATE' | 'DUPLICATE_ALTERNATE_KEY' | 'COLUMN_UNAVAILABLE' | null;
  duplicateOfIdentifierId: number | null; duplicateOfIdentifierName: string | null; warnings: string[]; message: string;
}
type FkStatus = 'READY' | 'NO_PAIRS' | 'PARTIAL_PAIRS' | 'IDENTIFIER_MISMATCH' | 'PARENT_TABLE_UNAVAILABLE' | 'COLUMN_UNAVAILABLE' | 'UNSUPPORTED' | 'CONFLICT';
type FkType = 'SINGLE' | 'SELF_SINGLE' | 'COMPOSITE' | 'SELF_COMPOSITE';
interface FkColumn { order: number; parentAttributeId: number; parentColumnName: string; childAttributeId: number; childColumnName: string }
const FK_TYPES: Record<FkType, string> = {
  SINGLE: t('일반 단일 FK'), SELF_SINGLE: t('자기 참조 단일 FK'), COMPOSITE: t('복합 FK'), SELF_COMPOSITE: t('자기 참조 복합 FK'),
};
const FK_STATUS: Record<FkStatus, string> = {
  READY: t('FK 생성'), NO_PAIRS: t('속성 짝 없음'), PARTIAL_PAIRS: t('속성 짝 일부'), IDENTIFIER_MISMATCH: t('부모 식별자 불일치'),
  PARENT_TABLE_UNAVAILABLE: t('부모 테이블 없음'), COLUMN_UNAVAILABLE: t('컬럼 없음'), UNSUPPORTED: t('표현할 수 없음'), CONFLICT: t('다른 FK 와 충돌'),
};
interface Plan {
  tables: TablePlan[]; creatable: boolean; planToken: string; dbType: string; identifiers: IdentifierIssue[]; relations: RelationPlan[];
  /** 지금 반영할 수 있는지 (M-7 2차) — 상속 구조 엔터티를 만드는 계획은 유효해도 반영하지 않는다 */
  executable?: boolean; executionBlockedReason?: string | null;
  inheritanceForeignKeys?: InheritanceForeignKeyPlan[]; requiredAncestors?: RequiredAncestor[]; constraintWarnings?: ConstraintWarning[];
  uniques?: UniquePlan[];
  ancestors?: AncestorUse[];
}

const IDENTIFIER_CODES: Record<IdentifierCode, string> = {
  IDENTIFIER_LENGTH_EXCEEDED: t('식별자 길이 초과'),
  IDENTIFIER_INVALID_CHARACTER: t('DBMS 허용 문자 위반'),
  IDENTIFIER_NOT_RECOMMENDED: t('권장 형식 아님'),
  CONSTRAINT_NAME_TRUNCATED: t('제약조건명 잘림'),
  CONSTRAINT_NAME_COLLISION: t('제약조건명 충돌'),
};
const RELATION_REASONS: Record<RelationReason, string> = {
  ATTRIBUTE_PAIRS_MISSING: t('속성 짝 미지정'),
  ATTRIBUTE_PAIRS_INCOMPLETE: t('속성 짝 일부'),
  ATTRIBUTE_PAIRS_INVALID: t('부모 식별자 불일치'),
  COLUMN_UNAVAILABLE: t('부모·자식 컬럼 없음'),
  PARENT_KEY_NOT_TABLE_KEY: t('기존 부모 PK 와 다름'),
  SELF_KEY_ONLY: t('자식 = 부모 키 자신'),
  TYPE_INCOMPATIBLE: t('타입 불일치'),
  CHILD_COLUMN_IN_USE: t('자식 속성 중복 사용'),
  SELF_REFERENCE: t('자기 참조'),
  MULTIPLE_RELATIONSHIPS: t('같은 부모의 관계 여러 개'),
  COMPOSITE_PARENT_KEY: t('부모 복합 식별자'),
  PARENT_KEY_MISSING: t('부모 주 식별자 없음'),
  UNIQUE_ORDER_DIFFERS: t('보조 식별자 순서와 다름'),
  PARENT_UNIQUE_MISSING: t('기존 부모 UNIQUE 없음'),
  PARENT_TABLE_MISSING: t('부모 테이블 없음'),
  INHERITED_KEY_MAPPING_MISSING: t('부모 상속 PK 매핑 없음'),
};
/** 상속 FK 를 만들 수 없는 사유 (M-7 2·3차 — 서버 코드) */
const INHERITANCE_REASONS: Record<string, string> = {
  ROOT_KEY_MISSING: t('루트 주 식별자 없음'),
  ANCESTOR_MISSING: t('상위 엔터티 테이블 없음'),
  ROOT_KEY_MAPPING_MISSING: t('루트 주 식별자 매핑 없음'),
  PARENT_KEY_MISMATCH: t('기존 부모 PK 와 다름'),
  INHERITED_KEY_MAPPING_MISSING: t('부모 상속 PK 매핑 없음'),
  INHERITED_KEY_TYPE_MISMATCH: t('상속 PK 타입 불일치'),
  INHERITANCE_FK_MISSING: t('기존 부모의 상속 FK 없음'),
  ANCESTOR_INVALID: t('상위 상속 경로 불일치'),
  COLUMN_UNAVAILABLE: t('부모·자식 컬럼 없음'),
  TYPE_INCOMPATIBLE: t('타입 불일치'),
};
const ANCESTOR_STATES: Record<AncestorUse['state'], { label: string; variant: 'success' | 'accent' | 'danger' | 'attention' }> = {
  SELECTED: { label: t('이번에 생성'), variant: 'accent' },
  REUSE: { label: t('기존 테이블 재사용'), variant: 'success' },
  REVIEW: { label: t('기존 매핑 검토 필요'), variant: 'danger' },
  MISSING: { label: t('선택 필요'), variant: 'attention' },
};

const typeText = (c: ColumnPlan) => c.maxLength != null ? `${c.dataType}(${c.maxLength})` : c.precision != null ? `${c.dataType}(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : c.dataType;

/**
 * 논리 → 물리 선택 생성 (DEC-10) : 엔터티 고르기 → 미리보기(테이블·컬럼·PK, 막는 문제·경고, DBMS 식별자 진단, 관계별 FK 계획) → 확인 → 미승인 테이블·컬럼·매핑·FK 생성.
 * 미리보기 뒤 모델이 바뀌면 서버가 409 로 거절한다(다시 미리보기). 이미 매핑된 엔터티는 건너뛴다 — 기존 테이블을 덮어쓰지 않는다.
 * FK 는 논리 관계에 저장된 속성 짝이 완성된 관계만 만든다 (4차 — 나머지는 상태·사유만 보이고 생성을 막지 않는다). 식별자 진단은 경고만 하고 생성 가능 여부를 바꾸지 않는다.
 * 2차 : 미리보기의 엔터티마다 속성을 펼쳐 일반 속성을 빼고 다시 미리보기(주 식별자 속성은 필수 — 서버도 정규화), 오류 엔터티를 빼고 다시 미리보기, 반영 직전 모델 버전(서버)
 */
export function PhysicalGeneration({ modelId, schemas, onDone, onReview }: {
  modelId: number; schemas: Schema[]; onDone: (result: GenerationResult) => void;
  /** 기존 매핑 검토가 필요한 조상 : 매핑 관리 화면의 그 엔터티로 (대화상자를 닫고) */
  onReview?: (entityId: number) => void;
}) {
  // 엔터티 고르기 (M-7 4차) : 서버 페이징·검색 목록에서 고른다 (모델 전체를 받지 않는다). 고른 것은 ID → 이름으로 따로 들고 있어 페이지·검색이 바뀌어도 남는다
  const [input, setInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: ['generation-entities', modelId, keyword, page],
    queryFn: () => {
      const query = new URLSearchParams({ modelId: String(modelId), page: String(page), size: String(ENTITY_PAGE_SIZE), sort: 'entityName,asc', searchColumn: 'entityName', searchQuery: keyword });
      return api<{ data: MappingRow[]; recordsTotal: number }>(`/api/modelMapping/entity/data?${query}`, { silent: true });
    },
    placeholderData: (prev) => prev,
    gcTime: 0,
  });
  const total = list.data?.recordsTotal ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / ENTITY_PAGE_SIZE));
  const [picked, setPicked] = useState<Map<number, string>>(new Map());
  const [schemaId, setSchemaId] = useState(String(schemas.find((s) => s.isDefault)?.id ?? ''));
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 선택 제한 안내 (100개 초과 — 아무것도 바꾸지 않았다) */
  const [notice, setNotice] = useState<string | null>(null);
  /** 만들지 않을 속성 (일반 속성만 — 주 식별자 속성은 체크를 풀 수 없다) */
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  /** 미리보기가 지금 선택과 맞지 않으면(속성·스키마를 바꿈) 다시 미리보기 전까지 생성할 수 없다 — 이전 planToken 을 다시 쓰지 않는다 */
  const [stale, setStale] = useState(false);
  /** 속성 → 엔터티 (미리보기에서 알게 된 것) : 고르지 않은 엔터티의 속성은 보내지 않는다 (서버는 그런 ID 를 400 으로 거절한다) */
  const [owners, setOwners] = useState<Map<number, number>>(new Map());
  /** 마지막으로 보낸 미리보기 번호 : 늦게 온 이전 응답이 새 선택의 결과를 덮지 않게 한다 */
  const sequence = useRef(0);
  const body = (entityIds: number[], exclude: Set<number>, planToken?: string) =>
    ({ entityIds, schemaId: schemaId ? Number(schemaId) : null, planToken: planToken ?? null,
      excludedAttributeIds: [...exclude].filter((id) => entityIds.includes(owners.get(id) ?? -1)) });

  const preview = async (entityIds = [...picked.keys()], exclude = excluded) => {
    const mine = ++sequence.current;
    setBusy(true); setError(null); setNotice(null);
    try {
      const next = await api<Plan>('/api/modelMapping/generate/preview', { method: 'POST', body: body(entityIds, exclude), silent: true });
      if (mine !== sequence.current) return;
      setOwners((prev) => { const map = new Map(prev); next.tables.forEach((tp) => tp.attributes.forEach((a) => map.set(a.attributeId, tp.entityId))); return map; });
      setPlan(next); setStale(false);
    }
    catch (e) { if (mine === sequence.current) setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.')); }
    finally { if (mine === sequence.current) setBusy(false); }
  };
  const apply = async () => {
    if (!plan || stale) return;
    const mine = ++sequence.current;
    setBusy(true); setError(null);
    try { onDone(await api<GenerationResult>('/api/modelMapping/generate', { method: 'POST', body: body([...picked.keys()], excluded, plan.planToken), silent: true })); }
    // 실패(409 포함)는 성공으로 보이지 않는다. 선택·제외 속성은 그대로 두고 계획만 버린다 — 다시 미리보기해야 생성할 수 있다
    catch (e) { setError(e instanceof ApiError ? e.message : t('물리 테이블을 만들지 못했습니다.')); setPlan(null); }
    finally { if (mine === sequence.current) setBusy(false); }
  };
  /** 선택이 바뀌면 이전 계획(planToken)을 버린다 — 진행 중인 미리보기 응답도 무시한다 */
  const discardPlan = () => { sequence.current++; setPlan(null); setBusy(false); setNotice(null); };
  const toggle = (row: MappingRow) => {
    if (!picked.has(row.entityId) && picked.size >= GENERATION_MAX_ENTITIES) {
      setNotice(t('한 번에 최대 {0}개까지 고를 수 있습니다. 다른 엔터티를 먼저 빼주세요.', GENERATION_MAX_ENTITIES));
      return;
    }
    discardPlan();
    setPicked((prev) => { const next = new Map(prev); if (next.has(row.entityId)) next.delete(row.entityId); else next.set(row.entityId, row.entityName); return next; });
  };
  const unpick = (id: number) => { discardPlan(); setPicked((prev) => { const next = new Map(prev); next.delete(id); return next; }); };
  const clearPicked = () => { discardPlan(); setPicked(new Map()); setExcluded(new Set()); };
  const toggleAttribute = (id: number) => {
    setStale(true);
    setExcluded((prev) => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  };
  // 오류 엔터티 제외 (P10) : 서버가 고르지 않는다 — 사용자가 빼고 새 미리보기(새 planToken)를 받는다
  const errorEntities = plan ? plan.tables.filter((tp) => tp.status === 'ERROR').map((tp) => tp.entityId) : [];
  const withoutErrors = [...picked.keys()].filter((id) => !errorEntities.includes(id));
  const dropErrorEntities = () => {
    const next = new Map([...picked].filter(([id]) => withoutErrors.includes(id)));
    const keep = new Set<number>();
    plan?.tables.filter((tp) => next.has(tp.entityId)).forEach((tp) => tp.attributes.forEach((a) => { if (excluded.has(a.attributeId)) keep.add(a.attributeId); }));
    setPicked(next); setExcluded(keep);
    void preview([...next.keys()], keep);
  };
  // 필요한 상위 엔터티 함께 선택 (M-7 4차) : 사용자가 누를 때만 미리보기의 '선택 필요' 조상을 선택에 더하고 다시 미리보기한다 (생성은 하지 않는다).
  // 100개를 넘으면 아무것도 더하지 않는다 — 일부만 더하거나 기존 선택을 빼거나 요청을 나누지 않는다
  const ancestors: AncestorUse[] = plan?.ancestors ?? (plan?.requiredAncestors ?? []).map((a) => ({ ...a, state: 'MISSING' as const, mappingId: null, tableName: null }));
  const missing = ancestors.filter((a) => a.state === 'MISSING' && !picked.has(a.entityId));
  const includeAncestors = () => {
    const after = picked.size + missing.length;
    if (after > GENERATION_MAX_ENTITIES) {
      setNotice(t('필요한 상위 엔터티를 함께 고르면 {0}개가 되어 최대 {1}개를 넘습니다 (지금 {2}개 + 추가 {3}개). 고른 엔터티를 줄인 뒤 다시 시도해주세요. 아무것도 추가하지 않았습니다.',
        after, GENERATION_MAX_ENTITIES, picked.size, missing.length));
      return;
    }
    const next = new Map(picked);
    missing.forEach((a) => next.set(a.entityId, a.entityName));
    setPicked(next);
    void preview([...next.keys()], excluded);
  };
  const createCount = plan ? plan.tables.filter((tp) => tp.status === 'CREATE').length : 0;
  const skipCount = plan ? plan.tables.filter((tp) => tp.status === 'SKIP').length : 0;
  const columnCount = plan ? plan.tables.filter((tp) => tp.status === 'CREATE').reduce((sum, tp) => sum + tp.columns.length, 0) : 0;
  const nameOf = (id: number) => plan?.tables.find((x) => x.entityId === id)?.entityName ?? picked.get(id) ?? String(id);
  const search = () => { setPage(0); setKeyword(input.trim()); };

  return (
    <div className="term-relink">
      <p className="gh-muted">{t('고른 엔터티로 미승인 물리 테이블·컬럼을 만들고 매핑을 연결합니다. 테이블명은 표준 단어, 컬럼은 속성의 표준 용어·도메인을 따릅니다. 이미 매핑된 엔터티와 같은 이름의 테이블은 덮어쓰지 않습니다.')}</p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        <select className="form-control" style={{ maxWidth: 220 }} aria-label={t('스키마')} value={schemaId} onChange={(e) => { setSchemaId(e.target.value); discardPlan(); }}>
          {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}{s.isDefault ? t(' (기본)') : ''}</option>)}
        </select>
      </div>
      <div className="mapping-search">
        <input type="text" className="form-control" aria-label={t('엔터티 검색')} maxLength={100} placeholder={t('엔터티명')} value={input}
          onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } }} />
        <button type="button" className="btn" onClick={search}>{t('검색')}</button>
      </div>
      {list.isError ? <p role="alert">{t('엔터티 목록을 불러오지 못했습니다.')}</p>
        : !list.data ? <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>
          : list.data.data.length === 0 ? <p className="gh-muted">{keyword ? t('조건에 맞는 엔터티가 없습니다.') : t('이 논리 모델에 엔터티가 없습니다.')}</p>
            : (
              <ul className={`gen-entity-list${list.isFetching ? ' is-refreshing' : ''}`} aria-label={t('엔터티')}>
                {list.data.data.map((e) => (
                  <li key={e.entityId}>
                    <label>
                      <input type="checkbox" checked={picked.has(e.entityId)} onChange={() => toggle(e)} /> {e.entityName}
                      {e.inheritance && <> <InheritanceRoleLabel value={e.inheritance} /></>}
                      {e.mappingId != null && <> <Label title={t('이미 매핑된 엔터티는 만들지 않고 건너뜁니다 (상위 엔터티로는 재사용).')}>{t('매핑됨 {0}', e.tableName ?? '')}</Label></>}
                    </label>
                  </li>
                ))}
              </ul>
            )}
      {pageCount > 1 && (
        <div className="mapping-pager">
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('이전')}</button>
          <span className="gh-muted">{page + 1} / {pageCount}</span>
          <button type="button" className="btn btn-sm" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)}>{t('다음')}</button>
        </div>
      )}
      <div className="gen-picked" aria-live="polite">
        <span className={picked.size >= GENERATION_MAX_ENTITIES ? 'gen-picked-full' : undefined}>{t('고른 엔터티 {0} / {1}', picked.size, GENERATION_MAX_ENTITIES)}</span>
        {picked.size > 0 && <button type="button" className="logical-link" onClick={clearPicked}>{t('모두 빼기')}</button>}
      </div>
      {picked.size > 0 && (
        <details className="gen-picked-list">
          <summary>{t('고른 엔터티 보기 ({0})', picked.size)}</summary>
          <ul className="mapping-chips">
            {[...picked].map(([id, name]) => (
              <li key={id}><Label>{name} <button type="button" className="gen-unpick" aria-label={t('{0} 빼기', name)} onClick={() => unpick(id)}>×</button></Label></li>
            ))}
          </ul>
        </details>
      )}
      {notice && <div className="ai-dict-callout is-warn" role="alert">{notice}</div>}
      {plan && (
        <div style={{ overflowX: 'auto' }}>
          {plan.tables.map((tp) => {
            const tableIssues = plan.identifiers.filter((i) => i.entityId === tp.entityId);
            const flagged = (attributeId: number | null) => tableIssues.some((i) => i.attributeId === attributeId);
            const showRef = tp.columns.some((c) => c.refTableName);
            return (
              <details key={tp.entityId} className="gen-table" open={plan.tables.length <= 5 || tp.status === 'ERROR'}>
                <summary><strong>{tp.entityName}</strong> → <span className={`gh-mono${flagged(null) ? ' gen-ident-flag' : ''}`}>{tp.tableName ?? '-'}</span>{' '}
                  {/* UNSUPPORTED (M-6 후속 5차) : 슈퍼타입·서브타입 구조 — 만들지 않지만 다른 엔터티의 생성은 막지 않는다 */}
                  <Label variant={tp.status === 'CREATE' ? 'success' : tp.status === 'SKIP' || tp.status === 'UNSUPPORTED' ? 'attention' : 'danger'}>
                    {tp.status === 'CREATE' ? t('생성') : tp.status === 'SKIP' ? t('건너뜀') : tp.status === 'UNSUPPORTED' ? t('미지원 (상속 구조)') : t('오류')}
                  </Label>
                  {tp.inheritance && <> <Label variant="done">{INHERITANCE_ROLES[tp.inheritance.role]}</Label>{tp.inheritance.parentEntityName && <span className="gh-muted"> {t('상위 {0}', tp.inheritance.parentEntityName)}</span>}</>}
                  {tp.columns.length > 0 && <span className="gh-muted"> · {t('상속 PK {0} · 직접 속성 {1}', tp.columns.filter((c) => c.inheritedFromEntityId != null).length, tp.columns.filter((c) => c.inheritedFromEntityId == null).length)}</span>}
                </summary>
                {[...tp.errors, ...tp.warnings].length > 0 && <ul className="gh-muted">{[...tp.errors, ...tp.warnings].map((m) => <li key={m}>{m}</li>)}</ul>}
                {tp.attributes.length > 0 && (
                  <details className="gen-attributes">
                    <summary>{t('속성 선택 ({0}개 중 {1}개 제외)', tp.attributes.length, tp.attributes.filter((a) => excluded.has(a.attributeId) && !a.primary && !a.foreignKey).length)}</summary>
                    <ul>
                      {tp.attributes.map((a) => (
                        <li key={a.attributeId}>
                          <label>
                            <input type="checkbox" checked={a.primary || a.foreignKey || !excluded.has(a.attributeId)} disabled={a.primary || a.foreignKey || busy}
                              onChange={() => toggleAttribute(a.attributeId)} /> {a.attributeName}
                            {a.primary && <> <Label variant="accent">{t('PK 필수')}</Label></>}
                            {a.foreignKey && <> <Label variant="done">{t('FK 필수')}</Label></>}
                          </label>
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
                {tp.columns.length > 0 && (
                  <table className="term-relink-table">
                    <thead><tr><th>{t('속성')}</th><th>{t('컬럼')}</th><th>{t('데이터 타입')}</th><th>PK</th><th>NULL</th>{showRef && <th>{t('참조 테이블')}</th>}</tr></thead>
                    <tbody>{tp.columns.map((c) => (
                      <tr key={c.attributeId} className={flagged(c.attributeId) ? 'gen-ident-row' : undefined}>
                        <td>{c.attributeName}{c.inheritedFromEntityId != null && <> <Label variant="accent">{t('상속 PK')}</Label></>}</td><td className="gh-mono">{c.columnName}</td><td className="gh-mono">{typeText(c)}</td><td>{c.pk ? 'Y' : ''}</td><td>{c.nullable ? 'Y' : 'N'}</td>
                        {showRef && <td className="gh-mono">{c.refTableName ?? ''}</td>}
                      </tr>
                    ))}</tbody>
                  </table>
                )}
              </details>
            );
          })}
          {ancestors.length > 0 && (
            <section className="gen-section" aria-label={t('필요한 상위 엔터티')}>
              <h4>{t('필요한 상위 엔터티')}</h4>
              <p className="gh-muted">{t('서브타입 테이블은 직계 상위 테이블을 참조합니다. 상위 엔터티는 이번에 함께 만들거나 유효한 기존 매핑 테이블을 재사용합니다. 서버는 상위 엔터티를 자동으로 추가하지 않습니다.')}</p>
              <ul className="gen-issues">
                {ancestors.map((a) => (
                  <li key={a.entityId}>
                    <Label variant={ANCESTOR_STATES[a.state].variant} dot>{ANCESTOR_STATES[a.state].label}</Label>{' '}
                    <strong>{a.entityName}</strong>{a.tableName && <> <span className="gh-mono">{a.tableName}</span></>}
                    <span className="gh-muted"> ← {a.requiredBy.map(nameOf).join(', ')}</span>
                    {a.state === 'REVIEW' && (
                      <div className="gh-muted">
                        {(a.reason && (INHERITANCE_REASONS[a.reason] ?? a.reason)) ?? ''} · {t('이미 매핑된 테이블의 구조가 맞지 않습니다. 함께 선택해도 해결되지 않습니다 (기존 테이블은 고치지 않습니다).')}
                        {onReview && <> <button type="button" className="logical-link" onClick={() => onReview(a.entityId)}>{t('매핑 검토')}</button></>}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
              {missing.length > 0 && (
                <div className="logical-actions">
                  <button type="button" className="btn" disabled={busy} onClick={includeAncestors}>{t('필요한 상위 엔터티 함께 선택 ({0}개)', missing.length)}</button>
                  <span className="gh-muted">{t('추가 후 {0} / {1}개 · 다시 미리보기만 하며 생성하지 않습니다.', picked.size + missing.length, GENERATION_MAX_ENTITIES)}</span>
                </div>
              )}
            </section>
          )}
          {(plan.inheritanceForeignKeys?.length ?? 0) > 0 && (
            <section className="gen-section" aria-label={t('상속 FK')}>
              <h4>{t('상속 FK')}</h4>
              <p className="gh-muted">{t('서브타입 테이블의 상속 PK(루트 주 식별자)가 직계 상위 테이블의 PK 를 참조합니다. 복합 키는 FK 하나입니다.')}</p>
              <ul className="gen-issues">
                {plan.inheritanceForeignKeys!.map((k) => (
                  <li key={k.childEntityId}>
                    <Label variant={k.status === 'READY' ? 'success' : 'attention'}>{k.status === 'READY' ? FK_STATUS.READY : t('FK 미생성')}</Label>{' '}
                    <strong>{k.parentEntityName}</strong> → <strong>{k.childEntityName}</strong>
                    {k.status === 'READY'
                      ? <div className="gen-fk"><span className="gh-mono">{k.childTableName} ({k.columns.map((c) => c.childColumnName).join(', ')}) → {k.parentTableName} ({k.columns.map((c) => c.parentColumnName).join(', ')})</span></div>
                      : <div className="gh-muted">{(k.reason && INHERITANCE_REASONS[k.reason]) ?? k.reason}</div>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {(plan.constraintWarnings?.length ?? 0) > 0 && (
            <section className="gen-section" aria-label={t('물리로 보장되지 않는 상속 제약')}>
              <h4>{t('물리로 보장되지 않는 상속 제약')}</h4>
              <p className="gh-muted">{t('경고만 하며 생성을 막지 않습니다. PK·FK 외의 제약(트리거·CHECK·구분 컬럼 복제)은 만들지 않습니다.')}</p>
              <ul className="gen-issues">{plan.constraintWarnings!.map((w) => <li key={`${w.groupId}-${w.constraint}`}><Label variant="attention">{w.constraint === 'DISJOINT' ? t('배타 미보장') : t('완전 미보장')}</Label> {w.message}</li>)}</ul>
            </section>
          )}
          {plan.identifiers.length > 0 && (
            <section className="gen-section" aria-label={t('DBMS 식별자 진단')}>
              <h4>{t('DBMS 식별자 진단')} <span className="gh-muted">({plan.dbType})</span></h4>
              <p className="gh-muted">{t('경고만 하며 생성은 막지 않습니다. 이름을 자동으로 고치지 않습니다.')}</p>
              <ul className="gen-issues">
                {plan.identifiers.map((i, n) => (
                  <li key={`${i.code}-${i.kind}-${i.entityId}-${i.attributeId}-${n}`}>
                    <Label variant={i.severity === 'ERROR' ? 'danger' : 'attention'}>{IDENTIFIER_CODES[i.code] ?? i.code}</Label>{' '}
                    <span className="gh-mono">{i.name}</span>{i.ddlName && i.ddlName !== i.name && <> → <span className="gh-mono">{i.ddlName}</span></>}
                    <div>{i.message}</div>
                    <div className="gh-muted">{t('기준')} : {i.basis}</div>
                    <div className="gh-muted">{i.guide}</div>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {(plan.uniques?.length ?? 0) > 0 && (
            <section className="gen-section" aria-label={t('보조 식별자 UNIQUE')}>
              <h4>{t('보조 식별자 UNIQUE')}</h4>
              <p className="gh-muted">{t('새로 만드는 테이블에만 보조 식별자마다 명시적 UNIQUE 제약조건을 식별자 속성 순서대로 미승인으로 만듭니다. 주 식별자와 같은 구성, 같은 구성의 다른 보조 식별자는 하나만 만듭니다. NULL 허용 컬럼은 경고만 합니다.')}</p>
              <ul className="gen-issues">
                {plan.uniques!.map((u) => (
                  <li key={u.identifierId}>
                    <Label variant={u.status === 'CREATE' ? 'success' : 'attention'}>{u.status === 'CREATE' ? t('UNIQUE 생성') : t('만들지 않음')}</Label>{' '}
                    <strong>{u.entityName}</strong> · {u.identifierName}
                    {u.status === 'CREATE' && <div className="gen-fk"><span className="gh-mono">{u.constraintName} ({u.columns.join(', ')})</span></div>}
                    <div className="gh-muted">{u.message}</div>
                    {u.warnings.includes('NULLABLE_COLUMNS') && <div className="gh-muted">{t('NULL 허용 컬럼이 있습니다. NULL 값은 UNIQUE 로 중복을 막지 못하며 필수 여부는 바꾸지 않습니다.')}</div>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {plan.relations.length > 0 && (
            <section className="gen-section" aria-label={t('논리 관계 FK')}>
              <h4>{t('논리 관계 FK')}</h4>
              <p className="gh-muted">{t('논리 관계에 지정한 속성 짝이 완성된 관계만 FK 를 만듭니다 (일반 단일 FK 는 자식 컬럼의 참조 테이블, 자기 참조·복합 FK 는 관계로). 만들 수 없는 관계는 사유와 함께 FK 없이 만듭니다. 속성 짝은 논리 모델 관리·논리 ERD 의 관계에서 지정합니다.')}</p>
              <ul className="gen-issues">
                {plan.relations.map((r) => (
                  <li key={r.relationshipId}>
                    <Label variant={r.fkCreated ? 'success' : 'attention'}>{r.fkCreated ? FK_STATUS.READY : t('FK 미생성')}</Label>{' '}
                    {r.fkType && <><Label>{FK_TYPES[r.fkType]}</Label>{' '}</>}
                    {r.keyType === 'ALTERNATE' && <><Label variant="accent">{t('보조 식별자 참조')}</Label>{' '}</>}
                    <strong>{r.parentEntityName}</strong> → <strong>{r.childEntityName}</strong>{r.relationshipName && <span className="gh-muted"> ({r.relationshipName})</span>}
                    {r.approved === false && <> <Label variant="attention">{t('승인 전')}</Label></>}
                    {!r.fkCreated && <div className="gh-muted">{FK_STATUS[r.fkStatus] ?? r.fkStatus} · {r.message}</div>}
                    {r.fkCreated && (
                      <div className="gen-fk">
                        <span className="gh-mono">{r.childTableName} ({r.columns.map((c) => c.childColumnName).join(', ')}) → {r.parentTableName} ({r.columns.map((c) => c.parentColumnName).join(', ')})</span>
                        <div className="gh-muted">{t('제약조건명')} <span className="gh-mono">{r.constraintName}</span>{r.parentTableId != null && <> · {t('기존 부모 테이블')}</>}
                          {r.keyType === 'ALTERNATE' && <> · {t('보조 식별자 {0} 의 UNIQUE 참조 · 미승인 사용자 관계', r.keyIdentifierName ?? '-')}</>}</div>
                      </div>
                    )}
                    <div className="gen-reasons">{r.reasons.map((reason) => <Label key={reason}>{RELATION_REASONS[reason] ?? reason}</Label>)}</div>
                    <div className="gen-pairs"><PairStatusLabel state={{ status: r.pairStatus, warnings: r.pairWarnings, pairs: r.pairs, parentKeySize: 0 }} /> <PairList state={{ status: r.pairStatus, warnings: r.pairWarnings, pairs: r.pairs, parentKeySize: 0 }} /></div>
                    {/* 짝 경고(순서·자기 키·P4·P5)는 라벨의 툴팁만으로는 터치·키보드에서 보이지 않는다 — 본문으로 (5차) */}
                    {r.pairWarnings.length > 0 && <ul className="logical-pairs-warnings">{r.pairWarnings.map((w) => <li key={w}>{PAIR_WARNINGS[w]}</li>)}</ul>}
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}
      {plan && !stale && plan.creatable && plan.executable === false && (
        <div className="ai-dict-callout is-warn" role="status">{t('이 계획은 지금 반영할 수 없습니다. 미리보기를 다시 확인해주세요.')}</div>
      )}
      {plan && stale && <div className="ai-dict-callout is-warn" role="status">{t('선택을 바꿨습니다. 다시 미리보기해야 생성할 수 있습니다.')}</div>}
      {plan && !stale && (
        <p className="gh-muted gen-summary">{t('만들 테이블 {0}개 · 컬럼 {1}개 · 건너뜀 {2}개 · 오류 엔터티 {3}개 · 상속 FK {4}개 · 관계 FK {5}개 (FK 없이 만드는 관계 {6}개). 생성 직전 모델 버전을 남깁니다.',
          createCount, columnCount, skipCount, errorEntities.length, (plan.inheritanceForeignKeys ?? []).filter((k) => k.status === 'READY').length,
          plan.relations.filter((r) => r.fkCreated).length, plan.relations.filter((r) => !r.fkCreated).length)}</p>
      )}
      {!plan && picked.size > 0 && !busy && !error && <p className="gh-muted" role="status">{t('선택이 바뀌었습니다. 미리보기로 계획을 확인한 뒤 생성할 수 있습니다.')}</p>}
      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        {errorEntities.length > 0 && (
          <button type="button" className="btn" disabled={busy || withoutErrors.length === 0} onClick={dropErrorEntities}
            title={withoutErrors.length === 0 ? t('모든 엔터티에 오류가 있습니다.') : undefined}>{t('오류 엔터티 제외 후 다시 미리보기')}</button>
        )}
        <button type="button" className="btn" disabled={busy || picked.size === 0} onClick={() => void preview()}>{t('미리보기')}</button>
        <button type="button" className="btn btn-primary" disabled={busy || stale || !(plan?.executable ?? plan?.creatable)} onClick={() => void apply()}
          title={!plan ? t('미리보기를 먼저 실행해주세요.') : stale ? t('선택을 바꿨습니다. 다시 미리보기해주세요.') : !(plan.executable ?? plan.creatable) ? t('오류 엔터티가 있거나 만들 테이블이 없습니다.') : undefined}>{t('물리 테이블 생성')}</button>
      </div>
    </div>
  );
}
