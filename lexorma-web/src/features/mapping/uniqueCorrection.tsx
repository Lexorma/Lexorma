import { useEffect, useRef, useState } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/term-relink.css';
import '../../styles/ai-dictionary.css';

/**
 * 기존 물리 테이블 누락 UNIQUE 보정 (M7-AK-02, docs/completed/alternate-key-unique-m7.md) — 정합성 비교의 [보조 식별자·UNIQUE] 범위에서 UNIQUE 누락 보조 식별자를 골라
 * 미리보기 → 확인 → 적용. 보정 가능 여부·사유는 서버만 판정한다. 기존 UNIQUE·고유 인덱스·컬럼(고유값·필수 여부)은 바꾸지 않는다.
 */

/** UniqueCorrectionDto.Code */
export type UniqueCode =
  | 'NOT_MISSING' | 'MAPPING_MISSING' | 'COLUMN_INVALID' | 'PRIMARY_KEY_DUPLICATE' | 'UNIQUE_DUPLICATE' | 'UNIQUE_INDEX_DUPLICATE'
  | 'COLUMN_UNIQUE_DUPLICATE' | 'DUPLICATE_ALTERNATE_KEY' | 'NAME_CONFLICT' | 'APPROVED_TABLE' | 'APPROVED_COLUMN';

export interface UniqueIssue { code: UniqueCode; reason: string }

export interface UniqueColumn {
  order: number; attributeId: number | null; attributeName: string | null; columnId: number | null; columnName: string | null;
  dataType: string | null; nullable: boolean | null; approved: boolean | null;
}

/** UniqueCorrectionDto.Item */
export interface UniqueItem {
  identifierId: number; identifierName: string | null; logicalValue: string | null; entityId: number | null; entityName: string | null;
  tableId: number | null; schemaName: string | null; tableName: string | null; diagnosisCode: string; constraintName: string | null; approved: boolean;
  columns: UniqueColumn[]; warnings: string[]; correctable: boolean; blockers: UniqueIssue[];
  duplicateOfIdentifierId: number | null; duplicateOfIdentifierName: string | null;
}

export interface UniquePlan { modelId: number; items: UniqueItem[]; total: number; correctable: number; blocked: number; applicable: boolean; planToken: string }
export interface UniqueResult { selected: number; created: number; constraintIds: number[]; versionId: number; versionName: string }

/** 서버 UniqueCorrectionDto.MAX_IDENTIFIERS 와 같다 (서버가 다시 검사한다) */
export const UNIQUE_CORRECTION_MAX = 100;

export const UNIQUE_CODE_LABELS: Record<UniqueCode, string> = {
  NOT_MISSING: t('UNIQUE 누락 아님'),
  MAPPING_MISSING: t('매핑 없음'),
  COLUMN_INVALID: t('구성 컬럼 오류'),
  PRIMARY_KEY_DUPLICATE: t('PK 와 같은 구성'),
  UNIQUE_DUPLICATE: t('같은 UNIQUE 있음'),
  UNIQUE_INDEX_DUPLICATE: t('같은 고유 인덱스 있음'),
  COLUMN_UNIQUE_DUPLICATE: t('이미 고유값 컬럼'),
  DUPLICATE_ALTERNATE_KEY: t('같은 구성 보조 식별자'),
  NAME_CONFLICT: t('UNIQUE 이름 충돌'),
  APPROVED_TABLE: t('승인된 테이블'),
  APPROVED_COLUMN: t('승인된 컬럼'),
};

const issueText = (issues: UniqueIssue[]) => issues.map((i) => i.reason).join('\n');
const nullableWarning = (item: UniqueItem) => item.warnings.includes('NULLABLE_COLUMNS');

/** 목록 칸 : 보정 가능 / 보정 불가(첫 사유) */
export function UniqueLabel({ item }: { item: UniqueItem | undefined }) {
  if (!item) return null;
  if (item.correctable) {
    return (
      <span className="rename-cell" title={nullableWarning(item) ? t('NULL 을 허용하는 구성 컬럼이 있습니다 (NULL 값은 UNIQUE 로 중복을 막지 못합니다).') : undefined}>
        <Label variant="success">{t('보정 가능')}</Label>
        {nullableWarning(item) && <span className="rename-cell-code">{t('NULL 허용 경고')}</span>}
      </span>
    );
  }
  const first = item.blockers[0];
  return (
    <span className="rename-cell" title={issueText(item.blockers)}>
      <Label variant="danger">{t('보정 불가')}</Label>
      <span className="rename-cell-code">{first ? UNIQUE_CODE_LABELS[first.code] ?? first.code : ''}{item.blockers.length > 1 ? ` +${item.blockers.length - 1}` : ''}</span>
    </span>
  );
}

export function UniqueIssues({ issues }: { issues: UniqueIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <ul className="rename-issues">
      {issues.map((issue, index) => <li key={index}><Label variant="danger">{UNIQUE_CODE_LABELS[issue.code] ?? issue.code}</Label> {issue.reason}</li>)}
    </ul>
  );
}

const tablePath = (schema: string | null, table: string | null) => `${schema ? `${schema}.` : ''}${table ?? '-'}`;

/** 정합성 상세의 [UNIQUE 보정] 판정 (UNIQUE 누락 보조 식별자만) */
export function UniqueDetail({ item }: { item: UniqueItem | undefined }) {
  if (!item) return null;
  return (
    <div className="rename-detail">
      <h4 className="mapping-subtitle">{t('누락 UNIQUE 보정')} <UniqueLabel item={item} /></h4>
      {item.correctable
        ? <p className="form-help">{t('[보조 식별자·UNIQUE] 범위 목록에서 이 보조 식별자를 골라 UNIQUE 보정 미리보기를 할 수 있습니다. 만들 UNIQUE : {0} (미승인)', item.constraintName ?? '-')}</p>
        : <UniqueIssues issues={item.blockers} />}
      {nullableWarning(item) && <p className="form-help">{t('NULL 을 허용하는 구성 컬럼이 있습니다. NULL 값은 UNIQUE 로 중복을 막지 못하며, 필수 여부는 자동으로 바꾸지 않습니다.')}</p>}
    </div>
  );
}

/**
 * 보정 미리보기 → 확인 → 적용 (팝업 본문). FK 보정과 같은 규칙 : 막힌 항목이 하나라도 있으면 적용할 수 없고 (부분 적용 없음), 409 면 계획을 버리고 다시 미리보기해야 한다.
 */
export function UniquePreview({ modelId, ids, onRemove, onStale, onDone, onClose }: {
  modelId: number;
  ids: number[];
  onRemove: (ids: number[]) => void;
  onStale: () => void;
  onDone: (result: UniqueResult) => void;
  onClose: () => void;
}) {
  const dialog = useDialog();
  const [current, setCurrent] = useState(ids);
  const [plan, setPlan] = useState<UniquePlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const sequence = useRef(0);
  const applying = useRef(false);

  const preview = async (targets: number[]) => {
    const mine = ++sequence.current;
    setBusy(true); setError(null); setPlan(null);
    try {
      const next = await api<UniquePlan>('/api/modelMapping/quality/unique/preview', { method: 'POST', silent: true, body: { modelId, identifierIds: targets, planToken: null } });
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
    const blocked = plan.items.filter((i) => !i.correctable).map((i) => i.identifierId);
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
      const result = await api<UniqueResult>('/api/modelMapping/quality/unique/apply', {
        method: 'POST', silent: true, body: { modelId, identifierIds: plan.items.map((i) => i.identifierId), planToken: plan.planToken },
      });
      onDone(result);
    } catch (e) {
      setPlan(null);
      if (e instanceof ApiError && e.status === 409) {
        setStale(true);
        setError(t('다른 사용자가 모델을 변경했거나 다른 작업이 같은 테이블을 바꾸는 중입니다. 목록을 새로 고친 뒤 다시 미리보기를 진행해 주세요.'));
        onStale();
      } else {
        setError(e instanceof ApiError ? e.message : t('UNIQUE 를 보정하지 못했습니다.'));
      }
    } finally {
      applying.current = false;
      if (mine === sequence.current) setBusy(false);
    }
  };

  const confirmApply = () => {
    if (!plan || !plan.applicable || busy) return;
    dialog.confirm([
      t('보조 식별자 {0}개의 누락 UNIQUE 를 미승인 상태로 만듭니다.', plan.total),
      t('만들기 직전 상태를 모델 버전(UKFIX-…)으로 남기며, 하나라도 실패하면 전부 되돌립니다. 기존 UNIQUE·고유 인덱스·컬럼은 바꾸지 않습니다.'),
    ].join('\n'), () => void apply());
  };

  return (
    <div className="term-relink rename-preview" aria-busy={busy}>
      <p className="gh-muted">{t('고른 보조 식별자에 대응하는 명시적 UNIQUE 제약조건을 식별자 속성 순서대로 새로 만듭니다. 기존 UNIQUE 를 고치거나 지우지 않고, 컬럼의 고유값·필수 여부도 바꾸지 않습니다. 보정 가능 여부는 서버가 지금 상태로 다시 판정합니다.')}</p>
      {busy && !plan && <p className="gh-muted" role="status">{t('미리보기를 만드는 중입니다.')}</p>}
      {plan && (
        <>
          <p className="gh-muted" role="status">{t('선택 {0}개 · 보정 가능 {1}개 · 보정 불가 {2}개', plan.total, plan.correctable, plan.blocked)}</p>
          {!plan.applicable && (
            <div className="ai-dict-callout is-warn" role="status">
              {t('보정할 수 없는 보조 식별자가 있어 적용할 수 없습니다 (부분 적용은 하지 않습니다). 그 식별자를 선택에서 빼고 다시 미리보기해주세요.')}
            </div>
          )}
          <div className="rename-preview-scroll">
            <table className="term-relink-table rename-preview-table">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '22%' }}>{t('보조 식별자')}</th>
                  <th scope="col" style={{ width: '18%' }}>{t('테이블')}</th>
                  <th scope="col" style={{ width: '24%' }}>{t('UNIQUE 컬럼 (순서대로)')}</th>
                  <th scope="col" style={{ width: '14%' }}>{t('만들 UNIQUE')}</th>
                  <th scope="col">{t('판정')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.items.map((item) => (
                  <tr key={item.identifierId}>
                    <td>{item.logicalValue ?? item.identifierName ?? item.identifierId}{item.entityName && <div className="gh-muted">{item.entityName}</div>}</td>
                    <td><Mono>{tablePath(item.schemaName, item.tableName)}</Mono></td>
                    <td>
                      {item.columns.length === 0 ? <span className="gh-muted">-</span> : (
                        <ol className="fk-pairs">
                          {item.columns.map((c) => (
                            <li key={c.order}><Mono>{c.columnName ?? '-'}</Mono>{c.dataType && <span className="gh-muted"> {c.dataType}</span>}
                              {c.nullable && <span className="gh-muted"> · {t('NULL 허용')}</span>}</li>
                          ))}
                        </ol>
                      )}
                    </td>
                    <td>{item.constraintName ? <Mono>{item.constraintName}</Mono> : <span className="gh-muted">-</span>}
                      {item.correctable && <div className="gh-muted">{t('미승인 UNIQUE')}</div>}</td>
                    <td>
                      {item.correctable ? <Label variant="success">{t('보정 가능')}</Label> : <UniqueIssues issues={item.blockers} />}
                      {nullableWarning(item) && <div className="gh-muted">{t('NULL 허용 컬럼이 있습니다 (경고).')}</div>}
                    </td>
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
          onClick={confirmApply} title={!plan ? t('미리보기를 먼저 확인해주세요.') : !plan.applicable ? t('보정할 수 없는 보조 식별자가 있습니다.') : undefined}>
          <span className="motion-busy-label">{t('UNIQUE 보정 적용')}</span>
          {busy && plan && <span className="motion-spinner" aria-hidden="true" />}
        </button>
      </div>
    </div>
  );
}

export function uniqueResultMessage(result: UniqueResult) {
  return t('보조 식별자 {0}개 중 UNIQUE {1}개를 미승인 상태로 만들었습니다. 만들기 직전 모델 버전 {2} 을(를) 남겼습니다.', result.selected, result.created, result.versionName);
}
