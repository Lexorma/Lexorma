import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label } from '../../components/grid/cells';
import { ApiError, api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { Schema } from '../schema/schemas';
import type { PairState } from '../logical/model';
import { PAIR_WARNINGS, PairList, PairStatusLabel } from '../logical/relationshipPairs';

interface EntitySummary { id: number; entityName: string }
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
interface ConstraintWarning { groupId: number; groupName: string; supertypeEntityId: number; supertypeEntityName: string; constraint: 'DISJOINT' | 'TOTAL'; guarantee: string; message: string }
const INHERITANCE_ROLES: Record<InheritanceRole, string> = { ROOT: t('루트 슈퍼타입'), INTERMEDIATE: t('중간 슈퍼타입'), SUBTYPE: t('서브타입') };
export interface GenerationResult {
  tables: number; columns: number; tableIds: number[]; versionId: number | null; versionName: string | null; foreignKeys: number;
  /** M-7 3차 : 만든 상속 FK 수, 저장한 상속 PK 매핑 수 */
  inheritanceForeignKeys?: number; inheritedKeyMappings?: number;
}
type IdentifierCode = 'IDENTIFIER_LENGTH_EXCEEDED' | 'IDENTIFIER_INVALID_CHARACTER' | 'IDENTIFIER_NOT_RECOMMENDED' | 'CONSTRAINT_NAME_TRUNCATED' | 'CONSTRAINT_NAME_COLLISION';
interface IdentifierIssue {
  entityId: number | null; attributeId: number | null; code: IdentifierCode; severity: 'ERROR' | 'WARNING'; kind: string; name: string; ddlName: string | null;
  length: number | null; maxLength: number | null; basis: string; message: string; guide: string; related: string[];
}
type RelationReason = 'ATTRIBUTE_PAIRS_MISSING' | 'ATTRIBUTE_PAIRS_INCOMPLETE' | 'ATTRIBUTE_PAIRS_INVALID' | 'COLUMN_UNAVAILABLE' | 'PARENT_KEY_NOT_TABLE_KEY' | 'SELF_KEY_ONLY' | 'TYPE_INCOMPATIBLE' | 'CHILD_COLUMN_IN_USE' | 'SELF_REFERENCE' | 'MULTIPLE_RELATIONSHIPS' | 'COMPOSITE_PARENT_KEY' | 'PARENT_KEY_MISSING' | 'PARENT_TABLE_MISSING' | 'INHERITED_KEY_MAPPING_MISSING';
interface RelationPlan {
  relationshipId: number; relationshipName: string | null; parentEntityId: number; parentEntityName: string; childEntityId: number; childEntityName: string;
  fkCreated: boolean; reasons: RelationReason[]; message: string; approved: boolean | null;
  /** 논리 관계 속성 짝 (M-7 보강 3차) */
  pairStatus: PairState['status']; pairWarnings: PairState['warnings']; pairs: PairState['pairs'];
  /** FK 생성 계획 (M-7 보강 4차) */
  fkStatus: FkStatus; fkType: FkType | null; parentTableName: string | null; parentTableId: number | null; childTableName: string | null;
  constraintName: string | null; refTableName: string | null; columns: FkColumn[];
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
  COLUMN_UNAVAILABLE: t('부모·자식 컬럼 없음'),
  TYPE_INCOMPATIBLE: t('타입 불일치'),
};

const typeText = (c: ColumnPlan) => c.maxLength != null ? `${c.dataType}(${c.maxLength})` : c.precision != null ? `${c.dataType}(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : c.dataType;

/**
 * 논리 → 물리 선택 생성 (DEC-10) : 엔터티 고르기 → 미리보기(테이블·컬럼·PK, 막는 문제·경고, DBMS 식별자 진단, 관계별 FK 계획) → 확인 → 미승인 테이블·컬럼·매핑·FK 생성.
 * 미리보기 뒤 모델이 바뀌면 서버가 409 로 거절한다(다시 미리보기). 이미 매핑된 엔터티는 건너뛴다 — 기존 테이블을 덮어쓰지 않는다.
 * FK 는 논리 관계에 저장된 속성 짝이 완성된 관계만 만든다 (4차 — 나머지는 상태·사유만 보이고 생성을 막지 않는다). 식별자 진단은 경고만 하고 생성 가능 여부를 바꾸지 않는다.
 * 2차 : 미리보기의 엔터티마다 속성을 펼쳐 일반 속성을 빼고 다시 미리보기(주 식별자 속성은 필수 — 서버도 정규화), 오류 엔터티를 빼고 다시 미리보기, 반영 직전 모델 버전(서버)
 */
export function PhysicalGeneration({ modelId, schemas, onDone }: { modelId: number; schemas: Schema[]; onDone: (result: GenerationResult) => void }) {
  const { data: entities = [] } = useQuery({ queryKey: ['logical-entities', modelId], queryFn: () => api<EntitySummary[]>(`/api/logical/model/${enc(modelId)}/entity`) });
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [schemaId, setSchemaId] = useState(String(schemas.find((s) => s.isDefault)?.id ?? ''));
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 만들지 않을 속성 (일반 속성만 — 주 식별자 속성은 체크를 풀 수 없다) */
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  /** 미리보기가 지금 선택과 맞지 않으면(엔터티·속성·스키마를 바꿈) 다시 미리보기 전까지 생성할 수 없다 — 이전 planToken 을 다시 쓰지 않는다 */
  const [stale, setStale] = useState(false);
  /** 속성 → 엔터티 (미리보기에서 알게 된 것) : 고르지 않은 엔터티의 속성은 보내지 않는다 (서버는 그런 ID 를 400 으로 거절한다) */
  const [owners, setOwners] = useState<Map<number, number>>(new Map());
  const body = (entityIds: number[], exclude: Set<number>, planToken?: string) =>
    ({ entityIds, schemaId: schemaId ? Number(schemaId) : null, planToken: planToken ?? null,
      excludedAttributeIds: [...exclude].filter((id) => entityIds.includes(owners.get(id) ?? -1)) });

  const preview = async (entityIds = [...picked], exclude = excluded) => {
    setBusy(true); setError(null);
    try {
      const next = await api<Plan>('/api/modelMapping/generate/preview', { method: 'POST', body: body(entityIds, exclude), silent: true });
      setOwners((prev) => { const map = new Map(prev); next.tables.forEach((tp) => tp.attributes.forEach((a) => map.set(a.attributeId, tp.entityId))); return map; });
      setPlan(next); setStale(false);
    }
    catch (e) { setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.')); }
    finally { setBusy(false); }
  };
  const apply = async () => {
    if (!plan || stale) return;
    setBusy(true); setError(null);
    try { onDone(await api<GenerationResult>('/api/modelMapping/generate', { method: 'POST', body: body([...picked], excluded, plan.planToken), silent: true })); }
    catch (e) { setError(e instanceof ApiError ? e.message : t('물리 테이블을 만들지 못했습니다.')); setPlan(null); }
    finally { setBusy(false); }
  };
  const toggle = (id: number) => { setPlan(null); setPicked((prev) => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next; }); };
  const toggleAttribute = (id: number) => {
    setStale(true);
    setExcluded((prev) => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  };
  // 오류 엔터티 제외 (P10) : 서버가 고르지 않는다 — 사용자가 빼고 새 미리보기(새 planToken)를 받는다
  const errorEntities = plan ? plan.tables.filter((tp) => tp.status === 'ERROR').map((tp) => tp.entityId) : [];
  const withoutErrors = [...picked].filter((id) => !errorEntities.includes(id));
  const dropErrorEntities = () => {
    const next = new Set(withoutErrors);
    const keep = new Set<number>();
    plan?.tables.filter((tp) => next.has(tp.entityId)).forEach((tp) => tp.attributes.forEach((a) => { if (excluded.has(a.attributeId)) keep.add(a.attributeId); }));
    setPicked(next); setExcluded(keep);
    void preview([...next], keep);
  };
  const createCount = plan ? plan.tables.filter((tp) => tp.status === 'CREATE').length : 0;
  const columnCount = plan ? plan.tables.filter((tp) => tp.status === 'CREATE').reduce((sum, tp) => sum + tp.columns.length, 0) : 0;

  return (
    <div className="term-relink">
      <p className="gh-muted">{t('고른 엔터티로 미승인 물리 테이블·컬럼을 만들고 매핑을 연결합니다. 테이블명은 표준 단어, 컬럼은 속성의 표준 용어·도메인을 따릅니다. 이미 매핑된 엔터티와 같은 이름의 테이블은 덮어쓰지 않습니다.')}</p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        <select className="form-control" style={{ maxWidth: 220 }} aria-label={t('스키마')} value={schemaId} onChange={(e) => { setSchemaId(e.target.value); setStale(true); }}>
          {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}{s.isDefault ? t(' (기본)') : ''}</option>)}
        </select>
      </div>
      <ul style={{ maxHeight: 180, overflow: 'auto', listStyle: 'none', padding: 0, columns: 2 }} aria-label={t('엔터티')}>
        {entities.map((e) => (
          <li key={e.id}><label><input type="checkbox" checked={picked.has(e.id)} onChange={() => toggle(e.id)} /> {e.entityName}</label></li>
        ))}
      </ul>
      {plan && (
        <div style={{ overflowX: 'auto' }}>
          {plan.tables.map((tp) => {
            const tableIssues = plan.identifiers.filter((i) => i.entityId === tp.entityId);
            const flagged = (attributeId: number | null) => tableIssues.some((i) => i.attributeId === attributeId);
            const showRef = tp.columns.some((c) => c.refTableName);
            return (
              <section key={tp.entityId} style={{ marginBottom: 8 }}>
                <p><strong>{tp.entityName}</strong> → <span className={`gh-mono${flagged(null) ? ' gen-ident-flag' : ''}`}>{tp.tableName ?? '-'}</span>{' '}
                  {/* UNSUPPORTED (M-6 후속 5차) : 슈퍼타입·서브타입 구조 — 만들지 않지만 다른 엔터티의 생성은 막지 않는다 */}
                  <Label variant={tp.status === 'CREATE' ? 'success' : tp.status === 'SKIP' || tp.status === 'UNSUPPORTED' ? 'attention' : 'danger'}>
                    {tp.status === 'CREATE' ? t('생성') : tp.status === 'SKIP' ? t('건너뜀') : tp.status === 'UNSUPPORTED' ? t('미지원 (상속 구조)') : t('오류')}
                  </Label>
                  {tp.inheritance && <> <Label variant="done">{INHERITANCE_ROLES[tp.inheritance.role]}</Label>{tp.inheritance.parentEntityName && <span className="gh-muted"> {t('상위 {0}', tp.inheritance.parentEntityName)}</span>}</>}
                </p>
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
              </section>
            );
          })}
          {(plan.requiredAncestors?.length ?? 0) > 0 && (
            <section className="gen-section" aria-label={t('필요한 상위 엔터티')}>
              <h4>{t('필요한 상위 엔터티')}</h4>
              <p className="gh-muted">{t('서브타입 테이블은 상위 엔터티 테이블을 참조합니다. 아래 엔터티를 함께 고르거나 먼저 만들어주세요 (자동으로 추가하지 않습니다).')}</p>
              <ul className="gen-issues">
                {plan.requiredAncestors!.map((a) => (
                  <li key={a.entityId}><strong>{a.entityName}</strong> <span className="gh-muted">← {a.requiredBy.map((id) => plan.tables.find((x) => x.entityId === id)?.entityName ?? id).join(', ')}</span></li>
                ))}
              </ul>
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
          {plan.relations.length > 0 && (
            <section className="gen-section" aria-label={t('논리 관계 FK')}>
              <h4>{t('논리 관계 FK')}</h4>
              <p className="gh-muted">{t('논리 관계에 지정한 속성 짝이 완성된 관계만 FK 를 만듭니다 (일반 단일 FK 는 자식 컬럼의 참조 테이블, 자기 참조·복합 FK 는 관계로). 만들 수 없는 관계는 사유와 함께 FK 없이 만듭니다. 속성 짝은 논리 모델 관리·논리 ERD 의 관계에서 지정합니다.')}</p>
              <ul className="gen-issues">
                {plan.relations.map((r) => (
                  <li key={r.relationshipId}>
                    <Label variant={r.fkCreated ? 'success' : 'attention'}>{r.fkCreated ? FK_STATUS.READY : t('FK 미생성')}</Label>{' '}
                    {r.fkType && <><Label>{FK_TYPES[r.fkType]}</Label>{' '}</>}
                    <strong>{r.parentEntityName}</strong> → <strong>{r.childEntityName}</strong>{r.relationshipName && <span className="gh-muted"> ({r.relationshipName})</span>}
                    {r.approved === false && <> <Label variant="attention">{t('승인 전')}</Label></>}
                    {!r.fkCreated && <div className="gh-muted">{FK_STATUS[r.fkStatus] ?? r.fkStatus} · {r.message}</div>}
                    {r.fkCreated && (
                      <div className="gen-fk">
                        <span className="gh-mono">{r.childTableName} ({r.columns.map((c) => c.childColumnName).join(', ')}) → {r.parentTableName} ({r.columns.map((c) => c.parentColumnName).join(', ')})</span>
                        <div className="gh-muted">{t('제약조건명')} <span className="gh-mono">{r.constraintName}</span>{r.parentTableId != null && <> · {t('기존 부모 테이블')}</>}</div>
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
        <p className="gh-muted gen-summary">{t('만들 테이블 {0}개 · 컬럼 {1}개 · 오류 엔터티 {2}개. 생성 직전 모델 버전을 남깁니다.', createCount, columnCount, errorEntities.length)}</p>
      )}
      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        {errorEntities.length > 0 && (
          <button type="button" className="btn" disabled={busy || withoutErrors.length === 0} onClick={dropErrorEntities}
            title={withoutErrors.length === 0 ? t('모든 엔터티에 오류가 있습니다.') : undefined}>{t('오류 엔터티 제외 후 다시 미리보기')}</button>
        )}
        <button type="button" className="btn" disabled={busy || picked.size === 0} onClick={() => void preview()}>{t('미리보기')}</button>
        <button type="button" className="btn btn-primary" disabled={busy || stale || !(plan?.executable ?? plan?.creatable)} onClick={() => void apply()}>{t('물리 테이블 생성')}</button>
      </div>
    </div>
  );
}
