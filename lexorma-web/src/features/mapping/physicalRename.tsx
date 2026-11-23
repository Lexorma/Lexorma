import { useEffect, useRef, useState } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/term-relink.css';
import '../../styles/ai-dictionary.css';

/**
 * 물리명 추천 일괄 적용 (M-7 3차 화면, docs/completed/physical-name-recommendation-m7.md 11장) — 서버 2차 API 를 그대로 쓴다.
 * 판정(승인·용어·참조·충돌·제약조건명)은 서버만 한다 : 화면은 목록의 applicability 와 미리보기 결과를 보여 줄 뿐이다.
 */

export type RenameTarget = 'TABLE' | 'COLUMN';

/** PhysicalNameRecommendationDto.ApplyCode */
export type ApplyCode =
  | 'NOT_RECOMMENDED' | 'NO_CHANGE' | 'NOT_MAPPED' | 'NAME_CONFLICT' | 'RECOMMENDATION_DUPLICATE' | 'IDENTIFIER_INVALID' | 'IDENTIFIER_NOT_RECOMMENDED'
  | 'TABLE_APPROVED' | 'COLUMN_APPROVED' | 'TERM_MISMATCH' | 'COLUMN_TERM_MISSING' | 'CHECK_REFERENCE' | 'INDEX_REFERENCE' | 'VIEW_REFERENCE'
  | 'REFERENCE_UNVERIFIABLE' | 'CONSTRAINT_NAME_INVALID' | 'CONSTRAINT_NAME_CONFLICT' | 'RECOMMENDATION_CHANGED' | 'SOURCE_MAPPED' | 'INHERITED_READ_ONLY';

export interface ApplyIssue { code: ApplyCode; reason: string; related: string[] }

/** selectable = 막는 사유 없음. warnings 는 적용을 막지 않는다 (권장 형식 아님·원본 매핑) */
export interface Applicability { selectable: boolean; blockers: ApplyIssue[]; warnings: ApplyIssue[] }

/** PhysicalNameRenameDto.PlanItem */
export interface RenamePlanItem {
  type: RenameTarget; id: number; schemaId: number | null; schemaName: string | null; tableId: number | null; tableName: string | null;
  logicalId: number | null; logicalName: string | null; currentName: string | null; recommendedName: string | null; requestedName: string;
  modifiedAt: string | null; noChange: boolean; applicability: Applicability;
}

/** PhysicalNameRenameDto.Plan */
export interface RenamePlan {
  items: RenamePlanItem[]; total: number; tables: number; columns: number; selectable: number; blocked: number; warned: number;
  applicable: boolean; dbType: string; planToken: string;
}

/** PhysicalNameRenameDto.Result */
export interface RenameResult {
  tables: number; columns: number; total: number; versionId: number; versionName: string;
  warnings: { type: RenameTarget; id: number; name: string; warnings: ApplyIssue[] }[];
}

/** 고른 대상 1개 : 고를 때 본 추천명을 그대로 보낸다 (서버가 다시 계산해 다르면 RECOMMENDATION_CHANGED) */
export interface RenamePick { key: string; type: RenameTarget; id: number; recommendedName: string; currentName: string; label: string }

/** 테이블 + 컬럼 합 (P-07, 서버 PhysicalNameRenameDto.MAX_ITEMS 와 같다 — 서버가 다시 검사한다) */
export const RENAME_MAX = 500;

/** 선택 키 : 종류 + 물리 ID (테이블·컬럼 ID 가 같아도 섞이지 않는다) */
export const pickKey = (type: RenameTarget, id: number) => `${type}:${id}`;

/**
 * 목록에서 고를 수 있는가 : 서버가 고를 수 있다고 한 행, 또는 막는 사유가 추천끼리 충돌뿐인 행 — 목록의 추천끼리 충돌은 전체 후보 기준이고
 * 실제 판정은 고른 집합으로 미리보기에서 다시 한다 (P-12)
 */
export function listSelectable(a: Applicability | null | undefined) {
  if (!a) return false;
  return a.selectable || (a.blockers.length > 0 && a.blockers.every((b) => b.code === 'RECOMMENDATION_DUPLICATE'));
}

export const APPLY_CODE_LABELS: Record<ApplyCode, string> = {
  NOT_RECOMMENDED: t('추천 없음'),
  NO_CHANGE: t('변경 불필요'),
  NOT_MAPPED: t('매핑 없음'),
  NAME_CONFLICT: t('이름 충돌'),
  RECOMMENDATION_DUPLICATE: t('추천끼리 충돌'),
  IDENTIFIER_INVALID: t('식별자 규칙 위반'),
  IDENTIFIER_NOT_RECOMMENDED: t('권장 형식 아님'),
  TABLE_APPROVED: t('승인된 테이블'),
  COLUMN_APPROVED: t('승인된 컬럼'),
  TERM_MISMATCH: t('용어 불일치'),
  COLUMN_TERM_MISSING: t('표준 용어 없음'),
  CHECK_REFERENCE: t('CHECK 참조'),
  INDEX_REFERENCE: t('인덱스 식 참조'),
  VIEW_REFERENCE: t('VIEW 참조'),
  REFERENCE_UNVERIFIABLE: t('참조 판정 불가'),
  CONSTRAINT_NAME_INVALID: t('제약조건 이름 길이 초과'),
  CONSTRAINT_NAME_CONFLICT: t('제약조건 이름 충돌'),
  RECOMMENDATION_CHANGED: t('추천 이름 바뀜'),
  SOURCE_MAPPED: t('원본 DB 매핑'),
  INHERITED_READ_ONLY: t('상속 컬럼 (읽기 전용)'),
};

const issueText = (issues: ApplyIssue[]) => issues.map((i) => i.reason).join('\n');

/** 목록 칸 : 적용 가능 / 경고 / 선택에 따라 판정 / 변경 불필요 / 적용 불가(첫 사유) — 사유 전체는 title 과 상세에 있다 */
export function ApplicabilityLabel({ value }: { value: Applicability | null | undefined }) {
  if (!value) return null;
  if (value.selectable) {
    return value.warnings.length > 0
      ? <Label variant="attention" title={issueText(value.warnings)}>{t('적용 가능 · 경고')}</Label>
      : <Label variant="success">{t('적용 가능')}</Label>;
  }
  if (listSelectable(value)) return <Label variant="accent" title={issueText(value.blockers)}>{t('선택에 따라 판정')}</Label>;
  if (value.blockers.some((b) => b.code === 'NO_CHANGE')) return <Label>{t('변경 불필요')}</Label>;
  const first = value.blockers[0];
  // 좁은 칸 : 상태 이름표 아래에 첫 사유 코드를 줄바꿈해 적는다 (이름표가 옆 칸으로 넘치지 않게)
  return (
    <span className="rename-cell" title={issueText(value.blockers)}>
      <Label variant="danger">{t('적용 불가')}</Label>
      <span className="rename-cell-code">{first ? APPLY_CODE_LABELS[first.code] ?? first.code : ''}{value.blockers.length > 1 ? ` +${value.blockers.length - 1}` : ''}</span>
    </span>
  );
}

function IssueList({ issues, tone }: { issues: ApplyIssue[]; tone: 'danger' | 'attention' }) {
  if (issues.length === 0) return null;
  return (
    <ul className="rename-issues">
      {issues.map((issue, index) => (
        <li key={index}><Label variant={tone}>{APPLY_CODE_LABELS[issue.code] ?? issue.code}</Label> {issue.reason}</li>
      ))}
    </ul>
  );
}

/** 추천 상세의 일괄 적용 판정 : 목록 기준(추천끼리 충돌은 전체 후보 기준)이라는 것을 함께 알린다 */
export function ApplicabilityDetail({ value }: { value: Applicability | null | undefined }) {
  if (!value) return null;
  return (
    <div className="rename-detail">
      <h4 className="mapping-subtitle">{t('일괄 적용 판정')} <ApplicabilityLabel value={value} /></h4>
      <IssueList issues={value.blockers} tone={listSelectable(value) ? 'attention' : 'danger'} />
      <IssueList issues={value.warnings} tone="attention" />
      {listSelectable(value) && !value.selectable && (
        <p className="form-help">{t('추천끼리 충돌은 목록의 모든 후보를 기준으로 본 것입니다. 이 대상만 고르면 미리보기에서 함께 고른 대상 기준으로 다시 판정합니다.')}</p>
      )}
    </div>
  );
}

const itemPath = (item: RenamePlanItem) => {
  const schema = item.schemaName ? `${item.schemaName}.` : '';
  return item.type === 'TABLE' ? `${schema}${item.currentName ?? ''}` : `${schema}${item.tableName ?? ''}.${item.currentName ?? ''}`;
};

/**
 * 일괄 적용 미리보기 → 확인 → 적용 (팝업 본문). 열면 고른 집합으로 미리보기를 한 번 부른다.
 * 막힌 대상이 하나라도 있으면 적용할 수 없다 (부분 적용 없음 — P-05) : 사용자가 [적용 불가 항목 빼기]를 눌러야만 선택이 바뀌고, 그때 새 미리보기를 받는다.
 * 적용 실패는 다시 시도하지 않는다. 409 면 계획(planToken)을 버리고 목록을 새로 고치게 한 뒤 다시 미리보기를 받아야 한다.
 */
export function RenamePreview({ picks, onRemove, onStale, onDone, onClose }: {
  picks: RenamePick[];
  /** 고른 대상에서 뺀다 (사용자가 누른 경우에만) */
  onRemove: (keys: string[]) => void;
  /** 409 : 목록·요약을 새로 고친다 */
  onStale: () => void;
  onDone: (result: RenameResult) => void;
  onClose: () => void;
}) {
  const dialog = useDialog();
  const [current, setCurrent] = useState(picks);
  const [plan, setPlan] = useState<RenamePlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  /** 늦게 온 이전 응답이 새 결과를 덮지 않게 */
  const sequence = useRef(0);
  /** 적용 요청 중 (두 번 보내지 않는다) */
  const applying = useRef(false);

  const preview = async (targets: RenamePick[]) => {
    const mine = ++sequence.current;
    setBusy(true); setError(null); setPlan(null);
    try {
      const next = await api<RenamePlan>('/api/modelMapping/recommendation/rename/preview', {
        method: 'POST', silent: true,
        body: { items: targets.map((p) => ({ type: p.type, id: p.id, recommendedName: p.recommendedName })), planToken: null },
      });
      if (mine === sequence.current) { setPlan(next); setStale(false); }
    } catch (e) {
      if (mine === sequence.current) setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.'));
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  };

  useEffect(() => { void preview(picks); }, []); // eslint-disable-line react-hooks/exhaustive-deps -- 처음 열 때 한 번

  const removeBlocked = () => {
    if (!plan) return;
    const blocked = new Set(plan.items.filter((i) => !i.applicability.selectable).map((i) => pickKey(i.type, i.id)));
    const rest = current.filter((p) => !blocked.has(p.key));
    onRemove([...blocked]);
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
      const result = await api<RenameResult>('/api/modelMapping/recommendation/rename', {
        method: 'POST', silent: true,
        body: { items: plan.items.map((i) => ({ type: i.type, id: i.id, recommendedName: i.requestedName, baseModifiedAt: i.modifiedAt })), planToken: plan.planToken },
      });
      onDone(result);
    } catch (e) {
      // 실패는 성공으로 보이지 않는다. 계획은 버린다 — 같은 planToken 으로 다시 보내지 않는다
      setPlan(null);
      if (e instanceof ApiError && e.status === 409) {
        setStale(true);
        setError(t('다른 사용자가 모델을 변경했거나 추천 결과가 달라졌습니다. 다시 미리보기를 진행해 주세요.'));
        onStale();
      } else {
        setError(e instanceof ApiError ? e.message : t('이름을 바꾸지 못했습니다.'));
      }
    } finally {
      applying.current = false;
      if (mine === sequence.current) setBusy(false);
    }
  };

  const confirmApply = () => {
    if (!plan || !plan.applicable || busy) return;
    const lines = [
      t('테이블 {0}개 · 컬럼 {1}개, 모두 {2}개의 물리명을 바꿉니다.', plan.tables, plan.columns, plan.total),
      t('바꾸기 직전 상태를 모델 버전(RENAME-…)으로 남기며, 하나라도 실패하면 전부 되돌립니다. 한글명·표준 용어·매핑은 바뀌지 않습니다.'),
    ];
    if (plan.warned > 0) lines.push(t('경고가 있는 대상 {0}개 (원본 DB 매핑 등)가 함께 바뀝니다. 원본 DB 는 바꾸지 않습니다.', plan.warned));
    dialog.confirm(lines.join('\n'), () => void apply());
  };

  const sourceMapped = plan ? plan.items.filter((i) => i.applicability.warnings.some((w) => w.code === 'SOURCE_MAPPED')).length : 0;

  return (
    <div className="term-relink rename-preview" aria-busy={busy}>
      <p className="gh-muted">{t('고른 대상의 물리 테이블명·컬럼명을 추천명으로 바꿉니다. 추천명과 적용 가능 여부는 서버가 지금 상태로 다시 판정합니다 (함께 고른 대상끼리의 이름 충돌 포함).')}</p>
      {busy && !plan && <p className="gh-muted" role="status">{t('미리보기를 만드는 중입니다.')}</p>}
      {plan && (
        <>
          <p className="gh-muted" role="status">
            {t('전체 {0}개 · 적용 가능 {1}개 · 적용 불가 {2}개 · 경고 {3}개', plan.total, plan.selectable, plan.blocked, plan.warned)}
          </p>
          {!plan.applicable && (
            <div className="ai-dict-callout is-warn" role="status">
              {t('적용할 수 없는 대상이 있어 적용할 수 없습니다 (부분 적용은 하지 않습니다). 그 대상을 선택에서 빼고 다시 미리보기해주세요.')}
            </div>
          )}
          {sourceMapped > 0 && (
            <div className="ai-dict-callout is-warn" role="note">
              {t('원본 DB 와 매핑된 대상 {0}개 : 이름을 바꾸면 원본 DB 의 이름과 달라지고, 원본 비교에서 이름이 다르게 보이거나 원본 전용·모델 전용 항목으로 나올 수 있습니다. 원본 매핑은 해제되지 않고 원본 DB 는 바꾸지 않습니다.', sourceMapped)}
            </div>
          )}
          <div className="rename-preview-scroll">
            <table className="term-relink-table rename-preview-table">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '9%' }}>{t('종류')}</th>
                  <th scope="col" style={{ width: '27%' }}>{t('대상')}</th>
                  <th scope="col" style={{ width: '22%' }}>{t('변경 후')}</th>
                  <th scope="col" style={{ width: '14%' }}>{t('판정')}</th>
                  <th scope="col">{t('사유')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.items.map((item) => (
                  <tr key={pickKey(item.type, item.id)}>
                    <td>{item.type === 'TABLE' ? t('테이블') : t('컬럼')}</td>
                    <td><Mono>{itemPath(item)}</Mono>{item.logicalName && <div className="gh-muted">{item.logicalName}</div>}</td>
                    <td>{item.recommendedName ? <Mono>{item.recommendedName}</Mono> : <span className="gh-muted">-</span>}</td>
                    <td>
                      {item.applicability.selectable
                        ? (item.applicability.warnings.length > 0 ? <Label variant="attention">{t('적용 가능 · 경고')}</Label> : <Label variant="success">{t('적용 가능')}</Label>)
                        : item.noChange ? <Label>{t('변경 불필요')}</Label> : <Label variant="danger">{t('적용 불가')}</Label>}
                    </td>
                    <td>
                      <IssueList issues={item.applicability.blockers} tone="danger" />
                      <IssueList issues={item.applicability.warnings} tone="attention" />
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
        {plan && !plan.applicable && (
          <button type="button" className="btn" disabled={busy} onClick={removeBlocked}>{t('적용 불가 항목을 선택에서 빼고 다시 미리보기')}</button>
        )}
        {(stale || (!plan && error)) && current.length > 0 && (
          <button type="button" className="btn" disabled={busy} onClick={() => void preview(current)}>{t('다시 미리보기')}</button>
        )}
        <button type="button" className={`btn btn-primary${busy && plan ? ' is-busy' : ''}`} disabled={busy || !plan || !plan.applicable} aria-busy={busy}
          onClick={confirmApply} title={!plan ? t('미리보기를 먼저 확인해주세요.') : !plan.applicable ? t('적용할 수 없는 대상이 있습니다.') : undefined}>
          <span className="motion-busy-label">{t('이름 변경 적용')}</span>
          {busy && plan && <span className="motion-spinner" aria-hidden="true" />}
        </button>
      </div>
    </div>
  );
}

/** 적용 결과 문구 (공통 알림 대화상자) */
export function renameResultMessage(result: RenameResult) {
  const lines = [t('테이블 {0}개 · 컬럼 {1}개, 모두 {2}개의 물리명을 바꿨습니다. 바꾸기 직전 모델 버전 {3} 을(를) 남겼습니다.', result.tables, result.columns, result.total, result.versionName)];
  if (result.warnings.length > 0) {
    lines.push(t('경고가 있던 대상 {0}개 :', result.warnings.length));
    result.warnings.slice(0, 10).forEach((w) => lines.push(`· ${w.name} — ${w.warnings.map((x) => APPLY_CODE_LABELS[x.code] ?? x.code).join(', ')}`));
    if (result.warnings.length > 10) lines.push(t('… 외 {0}개', result.warnings.length - 10));
  }
  return lines.join('\n');
}
