import { useEffect, useRef, useState } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/term-relink.css';
import '../../styles/ai-dictionary.css';

/**
 * 기존 서브타입 누락 상속 FK 보정 (M7-INH-03, docs/completed/inheritance-reference-key-m7.md 5장) — 정합성 비교의 [상속 구조] 범위에서 상속 FK 가 없는 서브타입을 골라
 * 미리보기 → 확인 → 적용. 대상 ID 는 서브타입 엔터티 ID 다 (일반 FK 보정의 논리 관계 ID 와 섞지 않는다). 보정 가능 여부·사유·선행 보정 대상은 서버만 판정한다.
 */

/** InheritanceForeignKeyCorrectionDto.Code */
export type InheritanceCode =
  | 'STRATEGY_EXCLUDED' | 'NOT_SUBTYPE' | 'NOT_MAPPED' | 'ROOT_KEY_MISSING' | 'INHERITED_KEY_MAPPING_MISSING' | 'INHERITED_KEY_COLUMN_INVALID' | 'CHILD_KEY_MISMATCH'
  | 'CHILD_KEY_NULLABLE' | 'PARENT_UNMAPPED' | 'ANCESTOR_INVALID' | 'ANCESTOR_FK_PENDING' | 'TYPE_INCOMPATIBLE' | 'NOT_MISSING' | 'FK_COLUMNS' | 'FK_ORDER'
  | 'FK_TARGET' | 'RELATION_EXISTS' | 'REF_COLUMN_CONFLICT' | 'CHILD_TABLE_APPROVED' | 'CHILD_COLUMN_APPROVED' | 'NAME_CONFLICT' | 'STRUCTURE_INVALID';

export interface InheritanceIssue { code: InheritanceCode; reason: string }

export interface InheritancePair {
  order: number; childColumnId: number | null; childColumnName: string | null; childDataType: string | null;
  parentColumnId: number | null; parentColumnName: string | null; parentDataType: string | null;
}

/** InheritanceForeignKeyCorrectionDto.Item */
export interface InheritanceItem {
  subtypeEntityId: number; subtypeEntityName: string; supertypeEntityId: number | null; supertypeEntityName: string | null; groupId: number | null; groupName: string | null;
  childTableId: number | null; childSchemaName: string | null; childTableName: string | null; parentTableId: number | null; parentSchemaName: string | null; parentTableName: string | null;
  diagnosis: string | null; parentFkDetail: string | null; representation: 'USER_RELATION'; relationName: string | null; pairs: InheritancePair[]; correctable: boolean;
  blockers: InheritanceIssue[]; pendingAncestorEntityId: number | null; pendingAncestorEntityName: string | null; existingForeignKeys: string[];
}

export interface InheritancePlan { modelId: number; items: InheritanceItem[]; total: number; correctable: number; blocked: number; applicable: boolean; planToken: string }
export interface InheritanceResult { selected: number; created: number; relationIds: number[]; versionId: number; versionName: string }

/** 서버 InheritanceForeignKeyCorrectionDto.MAX_ENTITIES 와 같다 (서버가 다시 검사한다) */
export const INHERITANCE_CORRECTION_MAX = 100;

export const INHERITANCE_CODE_LABELS: Record<InheritanceCode, string> = {
  NOT_SUBTYPE: t('서브타입 아님'),
  NOT_MAPPED: t('테이블 매핑 없음'),
  ROOT_KEY_MISSING: t('루트 주 식별자 없음'),
  INHERITED_KEY_MAPPING_MISSING: t('상속 PK 매핑 누락'),
  INHERITED_KEY_COLUMN_INVALID: t('상속 PK 매핑 컬럼 오류'),
  CHILD_KEY_MISMATCH: t('자식 PK 구성 불일치'),
  CHILD_KEY_NULLABLE: t('상속 PK NULL 허용'),
  PARENT_UNMAPPED: t('직계 상위 매핑 없음'),
  ANCESTOR_INVALID: t('상위 경로 불일치'),
  ANCESTOR_FK_PENDING: t('상위 FK 먼저 보정'),
  TYPE_INCOMPATIBLE: t('타입 비호환'),
  NOT_MISSING: t('상속 FK 있음'),
  STRATEGY_EXCLUDED: t('상속 A·C형 대상 제외'),
  FK_COLUMNS: t('기존 FK 구성 불일치'),
  FK_ORDER: t('기존 FK 순서 불일치'),
  FK_TARGET: t('기존 FK 참조 대상 다름'),
  RELATION_EXISTS: t('같은 관계 있음'),
  REF_COLUMN_CONFLICT: t('참조 테이블명 충돌'),
  CHILD_TABLE_APPROVED: t('승인된 자식 테이블'),
  CHILD_COLUMN_APPROVED: t('승인된 자식 컬럼'),
  NAME_CONFLICT: t('FK 이름 충돌'),
  STRUCTURE_INVALID: t('상속 구조 판정 불가'),
};

const issueText = (issues: InheritanceIssue[]) => issues.map((i) => i.reason).join('\n');
const tablePath = (schema: string | null, table: string | null) => `${schema ? `${schema}.` : ''}${table ?? '-'}`;

/** 목록 칸 : 보정 가능 / 상위 먼저 / 보정 불가(첫 사유) */
export function InheritanceLabel({ item }: { item: InheritanceItem | undefined }) {
  if (!item) return null;
  if (item.correctable) return <Label variant="success">{t('보정 가능')}</Label>;
  const first = item.blockers[0];
  const pending = first?.code === 'ANCESTOR_FK_PENDING';
  const exists = first?.code === 'NOT_MISSING';
  return (
    <span className="rename-cell" title={issueText(item.blockers)}>
      <Label variant={pending ? 'attention' : exists ? undefined : 'danger'}>{pending ? t('상위 먼저') : exists ? t('보정 대상 아님') : t('보정 불가')}</Label>
      <span className="rename-cell-code">{first ? INHERITANCE_CODE_LABELS[first.code] ?? first.code : ''}{item.blockers.length > 1 ? ` +${item.blockers.length - 1}` : ''}</span>
    </span>
  );
}

export function InheritanceIssues({ issues }: { issues: InheritanceIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <ul className="rename-issues">
      {issues.map((issue, index) => (
        <li key={index}><Label variant={issue.code === 'ANCESTOR_FK_PENDING' ? 'attention' : 'danger'}>{INHERITANCE_CODE_LABELS[issue.code] ?? issue.code}</Label> {issue.reason}</li>
      ))}
    </ul>
  );
}

/** 정합성 상세의 [상속 FK 보정] 판정 (서브타입 행만) */
export function InheritanceDetail({ item }: { item: InheritanceItem | undefined }) {
  if (!item) return null;
  return (
    <div className="rename-detail">
      <h4 className="mapping-subtitle">{t('누락 상속 FK 보정')} <InheritanceLabel item={item} /></h4>
      {item.correctable
        ? <p className="form-help">{t('[상속 구조] 범위 목록에서 이 서브타입을 골라 상속 FK 보정 미리보기를 할 수 있습니다. 만들 FK : {0} (미승인)', item.relationName ?? '-')}</p>
        : <InheritanceIssues issues={item.blockers} />}
      {item.pendingAncestorEntityName && <p className="form-help">{t('먼저 보정할 상위 서브타입 : {0}', item.pendingAncestorEntityName)}</p>}
      {item.existingForeignKeys.length > 0 && (
        <p className="form-help">{t('직계 상위 테이블을 가리키는 기존 FK : {0}', item.existingForeignKeys.join(' / '))}</p>
      )}
    </div>
  );
}

/**
 * 보정 미리보기 → 확인 → 적용 (팝업 본문). 보정할 수 없는 서브타입이 하나라도 있으면 적용할 수 없다 (부분 적용 없음) — 사용자가 [보정 불가 항목 빼기]를 눌러야만 선택이 바뀐다.
 * 409 면 계획을 버리고 목록을 새로 고치게 한 뒤 다시 미리보기를 받아야 한다 (자동 재시도 없음). 상위 FK 를 보정해 하위가 가능해져도 새 미리보기가 필요하다
 */
export function InheritancePreview({ modelId, ids, onRemove, onStale, onDone, onClose }: {
  modelId: number;
  ids: number[];
  onRemove: (ids: number[]) => void;
  onStale: () => void;
  onDone: (result: InheritanceResult) => void;
  onClose: () => void;
}) {
  const dialog = useDialog();
  const [current, setCurrent] = useState(ids);
  const [plan, setPlan] = useState<InheritancePlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const sequence = useRef(0);
  const applying = useRef(false);

  const preview = async (targets: number[]) => {
    const mine = ++sequence.current;
    setBusy(true); setError(null); setPlan(null);
    try {
      const next = await api<InheritancePlan>('/api/modelMapping/quality/inheritanceFk/preview', { method: 'POST', silent: true, body: { modelId, entityIds: targets, planToken: null } });
      if (mine === sequence.current) { setPlan(next); setStale(false); }
    } catch (e) {
      if (mine === sequence.current) setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.'));
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  };

  useEffect(() => { void preview(ids); }, []); // eslint-disable-line react-hooks/exhaustive-deps -- 처음 열 때 한 번

  const removeBlocked = () => {
    if (!plan) return;
    const blocked = plan.items.filter((i) => !i.correctable).map((i) => i.subtypeEntityId);
    const rest = current.filter((id) => !blocked.includes(id));
    onRemove(blocked);
    setCurrent(rest);
    if (rest.length > 0) void preview(rest);
    else { setPlan(null); setError(null); }
  };

  const apply = async () => {
    if (!plan || !plan.applicable || applying.current) return;
    applying.current = true;
    const mine = ++sequence.current;
    setBusy(true); setError(null);
    try {
      const result = await api<InheritanceResult>('/api/modelMapping/quality/inheritanceFk/apply', {
        method: 'POST', silent: true, body: { modelId, entityIds: plan.items.map((i) => i.subtypeEntityId), planToken: plan.planToken },
      });
      onDone(result);
    } catch (e) {
      setPlan(null);
      if (e instanceof ApiError && e.status === 409) {
        setStale(true);
        setError(t('다른 사용자가 모델을 변경했거나 다른 작업이 같은 테이블을 바꾸는 중입니다. 목록을 새로 고친 뒤 다시 미리보기를 진행해 주세요.'));
        onStale();
      } else {
        setError(e instanceof ApiError ? e.message : t('상속 FK 를 보정하지 못했습니다.'));
      }
    } finally {
      applying.current = false;
      if (mine === sequence.current) setBusy(false);
    }
  };

  const confirmApply = () => {
    if (!plan || !plan.applicable || busy) return;
    dialog.confirm([
      t('서브타입 {0}개의 누락 상속 FK 를 미승인 사용자 관계로 만듭니다.', plan.total),
      t('만들기 직전 상태를 모델 버전(INHFIX-…)으로 남기며, 하나라도 실패하면 전부 되돌립니다. 기존 테이블·컬럼·PK·FK·매핑은 바꾸지 않습니다.'),
    ].join('\n'), () => void apply());
  };

  return (
    <div className="term-relink rename-preview" aria-busy={busy}>
      <p className="gh-muted">{t('고른 서브타입 테이블에 직계 상위 테이블을 상속 PK 그대로 참조하는 FK 를 새로 만듭니다. 기존 테이블·컬럼·PK·FK 는 바꾸지 않습니다. 만들 FK 구성과 보정 가능 여부는 서버가 지금 상태로 다시 판정합니다.')}</p>
      {busy && !plan && <p className="gh-muted" role="status">{t('미리보기를 만드는 중입니다.')}</p>}
      {plan && (
        <>
          <p className="gh-muted" role="status">{t('선택 {0}개 · 보정 가능 {1}개 · 보정 불가 {2}개', plan.total, plan.correctable, plan.blocked)}</p>
          {!plan.applicable && (
            <div className="ai-dict-callout is-warn" role="status">
              {t('보정할 수 없는 서브타입이 있어 적용할 수 없습니다 (부분 적용은 하지 않습니다). 그 서브타입을 선택에서 빼고 다시 미리보기해주세요.')}
            </div>
          )}
          <div className="rename-preview-scroll">
            <table className="term-relink-table rename-preview-table">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '18%' }}>{t('서브타입 → 직계 슈퍼타입')}</th>
                  <th scope="col" style={{ width: '22%' }}>{t('자식 → 부모 테이블')}</th>
                  <th scope="col" style={{ width: '20%' }}>{t('FK 컬럼 (순서대로)')}</th>
                  <th scope="col" style={{ width: '16%' }}>{t('만들 FK')}</th>
                  <th scope="col">{t('판정')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.items.map((item) => (
                  <tr key={item.subtypeEntityId}>
                    <td>{item.subtypeEntityName} → {item.supertypeEntityName ?? '-'}{item.groupName && <div className="gh-muted">{item.groupName}</div>}</td>
                    <td><Mono>{tablePath(item.childSchemaName, item.childTableName)}</Mono> → <Mono>{tablePath(item.parentSchemaName, item.parentTableName)}</Mono></td>
                    <td>
                      {item.pairs.length === 0 ? <span className="gh-muted">-</span> : (
                        <ol className="fk-pairs">
                          {item.pairs.map((p) => <li key={p.order}><Mono>{p.childColumnName ?? '-'}</Mono> → <Mono>{p.parentColumnName ?? '-'}</Mono></li>)}
                        </ol>
                      )}
                    </td>
                    <td>{item.relationName ? <Mono>{item.relationName}</Mono> : <span className="gh-muted">-</span>}
                      {item.correctable && <div className="gh-muted">{t('미승인 사용자 관계 (USER)')}</div>}</td>
                    <td>{item.correctable ? <Label variant="success">{t('보정 가능')}</Label> : <InheritanceIssues issues={item.blockers} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {error && <div className={`ai-dict-callout ${stale ? 'is-warn' : 'is-error'}`} role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" disabled={busy} onClick={onClose}>{t('닫기')}</button>
        {plan && !plan.applicable && <button type="button" className="btn" disabled={busy} onClick={removeBlocked}>{t('보정 불가 항목을 선택에서 빼고 다시 미리보기')}</button>}
        {(stale || (!plan && error)) && current.length > 0 && <button type="button" className="btn" disabled={busy} onClick={() => void preview(current)}>{t('다시 미리보기')}</button>}
        <button type="button" className={`btn btn-primary${busy && plan ? ' is-busy' : ''}`} disabled={busy || !plan || !plan.applicable} aria-busy={busy}
          onClick={confirmApply} title={!plan ? t('미리보기를 먼저 확인해주세요.') : !plan.applicable ? t('보정할 수 없는 서브타입이 있습니다.') : undefined}>
          <span className="motion-busy-label">{t('상속 FK 보정 적용')}</span>
          {busy && plan && <span className="motion-spinner" aria-hidden="true" />}
        </button>
      </div>
    </div>
  );
}

export function inheritanceResultMessage(result: InheritanceResult) {
  return t('서브타입 {0}개 중 상속 FK {1}개를 미승인 상태로 만들었습니다. 만들기 직전 모델 버전 {2} 을(를) 남겼습니다.', result.selected, result.created, result.versionName);
}
