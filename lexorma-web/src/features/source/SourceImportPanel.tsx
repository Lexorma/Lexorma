import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Eye, TriangleAlert } from 'lucide-react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { api, ApiError } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t, tc } from '../../lib/i18n';
import { actionLabel, sourceLabel, targetTypeLabel } from '../history/model';
import { Pager, useCanManageSource } from './shared';

/**
 * 원본 메타데이터 선택 가져오기 (M-4e) : 대상 고르기 → 미리보기(서버 계획, 저장 안 함) → 최종 확인 → 확정(서버가 다시 계획해 같을 때만 반영).
 * 고르지 않은 속성은 바뀌지 않는다. 같음·판단 불가·이름 변경 후보·충돌은 고를 수 없다 (자동 선택 없음). 확정 버튼은 프로젝트 관리자·ADMIN 에게만 보인다 (서버도 같은 기준).
 * 재가져오기 (M-4f) : 전에 가져온 TABLE 이면 마지막으로 가져온 값과 견줘 원본만 바뀐 것·모델에서 바꾼 것·둘 다 바뀐 것·기준값 없음을 나눠 보여 준다 (판정은 서버).
 */
type Kind = 'TABLE' | 'COLUMN' | 'PRIMARY_KEY' | 'UNIQUE' | 'CHECK' | 'FOREIGN_KEY' | 'INDEX';
type Action = 'CREATE' | 'ADD' | 'UPDATE';
type Reason = 'NOT_MAPPED' | 'MAPPING_CANDIDATE' | 'SCHEMA_UNMAPPED' | 'EXCLUDED' | 'NAME_TAKEN' | 'VIEW_NAME_TAKEN' | 'RECHECK' | 'MAPPING_CONFLICT' | 'SOURCE_REMOVED' | 'MODEL_DELETED'
  | 'SAME' | 'UNKNOWN' | 'RENAME_CANDIDATE' | 'CONFLICT' | 'REPLACE_REQUIRED' | 'MODEL_ONLY' | 'UNSUPPORTED_ATTRIBUTE' | 'APPROVED' | 'TYPE_NOT_REPRESENTABLE' | 'DEFAULT_NOT_REPRESENTABLE'
  | 'INVALID_NAME' | 'QUOTED_NAME' | 'PK_NOT_NULL' | 'PK_EXISTS' | 'PK_ORDER' | 'ORDER_NOT_SUPPORTED' | 'NO_FIELD' | 'EMPTY_COMMENT' | 'UNSUPPORTED_INDEX_TYPE'
  | 'MISSING_DEPENDENCY' | 'UNRESOLVED_TARGET' | 'TARGET_NOT_KEY' | 'NOT_FOUND' | 'UNSUPPORTED_DBMS' | 'MODEL_EDITED' | 'BOTH_CHANGED' | 'REMOVED_IN_SOURCE';
type Judgement = 'SOURCE_CHANGED' | 'MODEL_EDITED' | 'BOTH_CHANGED' | 'NO_BASELINE';

interface Item {
  id: string; kind: Kind | null; sourceName: string | null; modelName: string | null; action: Action | null; attribute: string | null; source: string | null;
  before: string | null; after: string | null; selectable: boolean; reason: Reason | null; excluded: string[]; dependsOn: string[];
  baseline: string | null; judgement: Judgement | null;
}
interface LastImport { executionId: number; reflectedOrder: number; userId: string | null; appliedAt: string; modelVersionName: string | null }
interface Candidates {
  connectionId: number; reflectedOrder: number; reflectedAt: string | null; modelSchemaName: string | null; modelTableName: string | null; create: boolean;
  tableReason: Reason | null; lastImport: LastImport | null; items: Item[];
}
interface Counts { tablesCreated: number; tablesChanged: number; columnsAdded: number; attributesChanged: number; constraintsAdded: number; indexesAdded: number; commentsChanged: number }
interface PlanTable { key: string; sourceTableName: string; modelSchemaName: string | null; modelTableName: string | null; create: boolean; items: Item[] }
interface Plan { connectionId: number; reflectedOrder: number; planToken: string; requestId: string; applicable: boolean; counts: Counts; tables: PlanTable[] }
interface Result { executionId: number; changeGroupId: string; modelVersionId: number; modelVersionName: string; counts: Counts }
interface Execution {
  id: number; connectionId: number; executionId: number | null; reflectedOrder: number; changeGroupId: string; userId: string | null; appliedAt: string;
  summary: { counts?: Counts; tables?: { sourceTable: string; modelTable: string; create: boolean; items: string[] }[]; modelVersionId?: number; modelVersionName?: string };
}
interface Change { targetType: string; targetId: number; targetName: string | null; action: string; source: string; before: string | null; after: string | null }

const KIND: Record<Kind, string> = { TABLE: 'TABLE', COLUMN: t('컬럼'), PRIMARY_KEY: 'PK', UNIQUE: 'UNIQUE', CHECK: 'CHECK', FOREIGN_KEY: 'FK', INDEX: t('인덱스') };
const ACTION: Record<Action, string> = { CREATE: t('새로 만들기'), ADD: t('추가'), UPDATE: t('변경') };
const ATTRIBUTE: Record<string, string> = {
  dataType: t('타입'), nullable: t('NULL 허용'), default: t('기본값'), comment: t('원본 COMMENT'), autoIncrement: t('자동 증가'), order: t('순서'),
};
const REASON: Record<Reason, string> = {
  NOT_MAPPED: t('유효한 매핑이 아닙니다'),
  MAPPING_CANDIDATE: t('같은 이름의 모델 TABLE 이 있습니다. 원본 매핑 관리에서 매핑을 확정하세요'),
  SCHEMA_UNMAPPED: t('원본 스키마 매핑이 없거나 유효하지 않습니다'),
  EXCLUDED: t('매핑 예외로 지정된 원본 TABLE 입니다 (원본 매핑 관리에서 예외를 취소하면 가져올 수 있습니다)'),
  NAME_TAKEN: t('같은 이름의 모델 TABLE 이 이미 있습니다'),
  VIEW_NAME_TAKEN: t('같은 이름의 모델 VIEW 가 있습니다'),
  RECHECK: t('원본이 다시 생겨 매핑 재확인이 필요합니다'),
  MAPPING_CONFLICT: t('매핑이 충돌합니다 (매핑 관리에서 확인)'),
  SOURCE_REMOVED: t('마지막 수집에서 원본이 사라졌습니다 (모델은 자동으로 지우지 않습니다)'),
  MODEL_DELETED: t('매핑된 모델 TABLE 이 삭제되었습니다'),
  SAME: t('차이가 없습니다'),
  UNKNOWN: t('판단 불가 항목은 가져오지 않습니다'),
  RENAME_CANDIDATE: t('이름 변경 후보는 가져오지 않습니다 (모델 관리에서 직접 처리)'),
  CONFLICT: t('충돌 항목은 가져오지 않습니다'),
  REPLACE_REQUIRED: t('기존 객체를 지우고 바꿔야 해서 가져오지 않습니다'),
  MODEL_ONLY: t('모델에만 있는 객체는 지우지 않습니다'),
  UNSUPPORTED_ATTRIBUTE: t('모델이 표현하지 못하는 원본 속성이 있어 가져오지 않습니다. 빼고 만들면 원본과 의미가 달라집니다 (모델 관리에서 직접 정하거나, 모델이 이 속성을 지원한 뒤 가져오세요)'),
  APPROVED: t('승인된 객체입니다. 승인 취소 후 다시 가져오세요'),
  TYPE_NOT_REPRESENTABLE: t('이 타입은 모델로 표현할 수 없습니다'),
  DEFAULT_NOT_REPRESENTABLE: t('이 기본값은 모델로 표현할 수 없습니다'),
  INVALID_NAME: t('모델에서 쓸 수 없는 이름입니다'),
  QUOTED_NAME: t('따옴표 식별자라 모델 DDL 로 같은 이름을 만들 수 없습니다'),
  PK_NOT_NULL: t('PK 컬럼은 NULL 을 허용할 수 없습니다'),
  PK_EXISTS: t('모델에 이미 PK 가 있습니다'),
  PK_ORDER: t('PK 컬럼 순서가 모델 컬럼 순서와 달라 표현할 수 없습니다'),
  ORDER_NOT_SUPPORTED: t('컬럼 순서는 가져오지 않습니다'),
  NO_FIELD: t('모델에 이 속성 칸이 없습니다'),
  EMPTY_COMMENT: t('원본 COMMENT 가 없어 기존 값을 지우지 않습니다'),
  UNSUPPORTED_INDEX_TYPE: t('모델이 지원하지 않는 인덱스 유형입니다'),
  MISSING_DEPENDENCY: t('선행 항목을 함께 고르세요'),
  UNRESOLVED_TARGET: t('FK 참조 대상 TABLE 이 매핑되지 않았거나 함께 가져오지 않습니다'),
  TARGET_NOT_KEY: t('참조 컬럼이 대상 TABLE 의 PK·UNIQUE 가 아닙니다'),
  NOT_FOUND: t('지금 비교 결과에 없는 항목입니다'),
  UNSUPPORTED_DBMS: t('이 DBMS 의 비교 규칙이 아직 없습니다'),
  MODEL_EDITED: t('원본은 마지막 가져오기 그대로인데 모델에서 바꿨습니다. 모델 값을 되돌리지 않습니다'),
  BOTH_CHANGED: t('마지막 가져오기 뒤 원본과 모델이 모두 바뀌었습니다. 자동으로 반영하지 않습니다 (모델 관리에서 직접 정하세요)'),
  REMOVED_IN_SOURCE: t('원본에서 삭제되었습니다. 모델 객체는 유지되며 자동 삭제는 지원하지 않습니다'),
};
const JUDGEMENT: Record<Judgement, { label: string; variant: LabelVariant; help: string }> = {
  SOURCE_CHANGED: { label: t('원본 변경'), variant: 'accent', help: t('마지막으로 가져온 뒤 원본만 바뀌었습니다') },
  MODEL_EDITED: { label: t('모델 수정됨'), variant: 'attention', help: t('마지막으로 가져온 뒤 모델에서 바꿨습니다') },
  BOTH_CHANGED: { label: t('양쪽 변경 충돌'), variant: 'danger', help: t('마지막으로 가져온 뒤 원본과 모델이 모두 바뀌었습니다') },
  NO_BASELINE: { label: t('기준값 없음'), variant: 'attention', help: t('마지막으로 가져온 값을 확인할 수 없습니다. 원본 값과 현재 모델 값을 견줘 직접 판단하세요') },
};

function JudgementLabel({ item }: { item: Item }) {
  if (!item.judgement) return null;
  const j = JUDGEMENT[item.judgement];
  return <div className="source-sub"><Label variant={j.variant} title={j.help}>{j.label}</Label></div>;
}

const IMPORT_KEY = 'source-import';

function itemLabel(item: Item) {
  return (
    <>
      {item.kind && <Label>{KIND[item.kind]}</Label>}{' '}
      <span className="gh-mono source-break">{item.sourceName ?? item.id}</span>
      {item.modelName && item.modelName !== item.sourceName && <span className="gh-muted"> → <span className="gh-mono">{item.modelName}</span></span>}
      <div className="gh-muted source-sub">
        {item.action ? ACTION[item.action] : ''}{item.attribute ? ` · ${ATTRIBUTE[item.attribute] ?? item.attribute}` : ''}
      </div>
    </>
  );
}

function Value({ value }: { value: string | null }) {
  return value == null ? <span className="gh-muted">-</span> : <span className="gh-mono source-break">{value}</span>;
}

function countsText(c: Counts) {
  return t('새 TABLE {0} · 바뀌는 TABLE {1} · 추가 컬럼 {2} · 속성 변경 {3} · 제약조건 {4} · 인덱스 {5} · 원본 COMMENT {6}',
    c.tablesCreated, c.tablesChanged, c.columnsAdded, c.attributesChanged, c.constraintsAdded, c.indexesAdded, c.commentsChanged);
}

/** TABLE 상세 대화상자 안 : 이 원본 TABLE 의 가져오기 */
export function SourceImportPanel({ rowKey, connectionId, reflectedOrder, onStale }: {
  rowKey: string; connectionId: number; reflectedOrder: number; onStale: () => void;
}) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const canManage = useCanManageSource();
  const [selected, setSelected] = useState<string[]>([]);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<{ ok: boolean; message: string } | null>(null);
  const params = new URLSearchParams({ key: rowKey, connectionId: String(connectionId), reflectedOrder: String(reflectedOrder) });
  const candidates = useQuery({
    queryKey: [IMPORT_KEY, 'candidates', connectionId, reflectedOrder, rowKey],
    queryFn: () => api<Candidates>(`/api/sourceImport/candidates?${params}`, { silent: true }),
    retry: false,
  });

  const body = (extra?: { planToken: string; requestId: string }) => ({
    connectionId, reflectedOrder, tables: [{ key: rowKey, tableName: null, items: selected }], planToken: extra?.planToken ?? null, requestId: extra?.requestId ?? null,
  });
  const toggle = (id: string) => {
    setPlan(null);
    setOutcome(null);
    setSelected((now) => (now.includes(id) ? now.filter((x) => x !== id) : [...now, id]));
  };
  const failure = (e: unknown) => {
    if (e instanceof ApiError && e.code === 'SOURCE_COMPARE_STALE') {
      setOutcome({ ok: false, message: `${e.message} ${t('물리 모델은 바뀌지 않았습니다.')}` });
      onStale();
    } else {
      setOutcome({ ok: false, message: `${(e as Error).message} ${t('물리 모델은 바뀌지 않았습니다.')}` });
    }
  };
  const preview = async () => {
    setBusy(true);
    setOutcome(null);
    try {
      setPlan(await api<Plan>('/api/sourceImport/plan', { method: 'POST', body: body(), silent: true }));
    } catch (e) {
      failure(e);
    } finally {
      setBusy(false);
    }
  };
  const apply = (current: Plan) => {
    const items = current.tables.flatMap((x) => x.items);
    const columns = new Set(items.filter((i) => i.kind === 'COLUMN').map((i) => i.sourceName ?? i.id)).size;
    const created = items.filter((i) => i.action === 'CREATE' || i.action === 'ADD').length;
    const notApplicable = candidates.data ? candidates.data.items.filter((i) => !i.selectable).length : 0;
    const detail = t('TABLE {0}개 · 컬럼 {1}개 · 고른 항목 {2}개 (새 객체 {3}개) · 가져올 수 없는 항목 {4}개는 반영하지 않습니다. 승인된 객체는 바꾸지 않습니다.',
      current.tables.filter((x) => x.items.length > 0).length, columns, items.length, created, notApplicable);
    const basis = t('직전 상태 모델 버전 : IMPORT-{0} · 기준 연결 #{1}, 반영 순서 {2}', current.requestId, current.connectionId, current.reflectedOrder);
    dialog.confirm(`${t('다음 내용을 물리 모델에 반영합니다. 반영 직전 상태를 모델 버전으로 남깁니다. 원본 DB 는 바뀌지 않고, 만든 객체는 미승인입니다. 계속하시겠습니까?\n{0}', countsText(current.counts))}\n${detail}\n${basis}`, async () => {
      setBusy(true);
      try {
        const result = await api<Result>('/api/sourceImport/apply', { method: 'POST', body: body({ planToken: current.planToken, requestId: current.requestId }), silent: true });
        setOutcome({ ok: true, message: t('가져오기를 반영했습니다 (이력 #{0}). 직전 상태는 모델 버전 {1} 에 남았습니다. {2}', result.executionId, result.modelVersionName, countsText(result.counts)) });
        setPlan(null);
        setSelected([]);
        await queryClient.invalidateQueries({ queryKey: ['source-compare'] });
        await queryClient.invalidateQueries({ queryKey: [IMPORT_KEY] });
      } catch (e) {
        failure(e);
      } finally {
        setBusy(false);
      }
    });
  };

  if (candidates.isError) return <p role="alert">{candidates.error.message}</p>;
  if (!candidates.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const data = candidates.data;
  const selectable = data.items.filter((i) => i.selectable);
  const conflicts = data.items.filter((i) => !i.selectable && (i.reason === 'MODEL_EDITED' || i.reason === 'BOTH_CHANGED'));
  const removed = data.items.filter((i) => i.reason === 'REMOVED_IN_SOURCE');
  const blocked = data.items.filter((i) => !i.selectable && !conflicts.includes(i) && !removed.includes(i));
  const reimport = data.lastImport != null;

  return (
    <section className="source-import" aria-label={t('가져오기')}>
      <h4 className="source-title-icon"><Download size={16} aria-hidden="true" /> {t('가져오기')}</h4>
      {data.tableReason ? <p className="gh-muted">{REASON[data.tableReason]}</p> : (
        <>
          <p className="source-import-mode">
            {reimport ? <Label variant="accent">{t('재가져오기')}</Label> : <Label>{t('처음 가져오기')}</Label>}{' '}
            <span className="gh-muted">
              {data.lastImport && <>{t('마지막 가져오기 #{0} · {1} · 반영 순서 {2}', data.lastImport.executionId, formatDateTime(data.lastImport.appliedAt), data.lastImport.reflectedOrder)} · </>}
              {t('현재 원본 수집 : 반영 순서 {0}', data.reflectedOrder)}{data.reflectedAt ? ` (${formatDateTime(data.reflectedAt)})` : ''}
            </span>
          </p>
          <p className="form-help">
            {data.create
              ? t('매핑되지 않은 원본 TABLE 입니다. TABLE:CREATE 를 고르면 {0}.{1} 를 새로 만들고 매핑을 함께 확정합니다.', data.modelSchemaName ?? '', data.modelTableName ?? '')
              : t('고른 항목만 반영합니다. 고르지 않은 속성은 바뀌지 않고, 삭제·이름 변경은 하지 않습니다. 원본 COMMENT 는 업무 설명과 따로 저장합니다.')}
          </p>
          {selectable.length === 0 ? <p className="gh-muted">{t('가져올 수 있는 차이가 없습니다.')}</p> : (
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{tc('열', '선택')}</th><th>{t('객체')}</th><th>{t('원본 값')}</th><th>{t('현재 모델')}</th>{reimport && <th>{t('마지막 가져온 값')}</th>}<th>{t('적용 후')}</th></tr></thead>
                <tbody>
                  {selectable.map((item) => (
                    <tr key={item.id}>
                      <td><input type="checkbox" aria-label={t('{0} 선택', item.id)} checked={selected.includes(item.id)} onChange={() => toggle(item.id)} /></td>
                      <td>
                        {itemLabel(item)}
                        {item.dependsOn.length > 0 && <div className="gh-muted source-sub">{t('함께 고를 항목 : {0}', item.dependsOn.join(', '))}</div>}
                        <JudgementLabel item={item} />
                      </td>
                      <td><Value value={item.source} /></td>
                      <td><Value value={item.before} /></td>
                      {reimport && <td><Value value={item.baseline} /></td>}
                      <td><Value value={item.after} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {conflicts.length > 0 && (
            <div className="source-import-conflicts" role="group" aria-label={t('확인이 필요한 변경')}>
              <h5 className="source-title-icon"><TriangleAlert size={14} aria-hidden="true" /> {t('확인이 필요한 변경 {0}개', conflicts.length)}</h5>
              <p className="form-help">{t('마지막으로 가져온 뒤 모델에서 바뀐 속성입니다. 덮어쓰지 않으므로 고를 수 없습니다. 원본 값을 쓰려면 모델 관리에서 직접 바꾸세요.')}</p>
              <div className="source-table-wrap">
                <table className="source-table">
                  <thead><tr><th>{t('객체')}</th><th>{t('원본 값')}</th><th>{t('현재 모델')}</th><th>{t('마지막 가져온 값')}</th><th>{t('사유')}</th></tr></thead>
                  <tbody>
                    {conflicts.map((item) => (
                      <tr key={item.id}>
                        <td>{itemLabel(item)}<JudgementLabel item={item} /></td>
                        <td><Value value={item.source} /></td>
                        <td><Value value={item.before} /></td>
                        <td><Value value={item.baseline} /></td>
                        <td className="gh-muted">{item.reason ? REASON[item.reason] : ''}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {removed.length > 0 && (
            <div className="source-import-removed" role="group" aria-label={t('원본에서 삭제됨')}>
              <h5 className="source-title-icon"><TriangleAlert size={14} aria-hidden="true" /> {t('원본에서 삭제됨 {0}개', removed.length)}</h5>
              <p className="form-help">{REASON.REMOVED_IN_SOURCE}</p>
              <ul>{removed.map((item) => <li key={item.id}>{item.kind && <Label>{KIND[item.kind]}</Label>} <span className="gh-mono source-break">{item.modelName}</span></li>)}</ul>
            </div>
          )}
          {blocked.length > 0 && (
            <details className="source-import-blocked">
              <summary>{t('가져올 수 없는 항목 {0}개', blocked.length)}</summary>
              <ul>
                {blocked.map((item) => (
                  <li key={item.id}>
                    <span className="gh-mono source-break">{item.id}</span> — <span className="gh-muted">{item.reason ? REASON[item.reason] : ''}</span>
                    {item.excluded.length > 0 && <div className="source-warn source-sub"><TriangleAlert size={12} aria-hidden="true" /> {t('원본 속성 : {0}', item.excluded.join(', '))}</div>}
                  </li>
                ))}
              </ul>
            </details>
          )}
          <div className="source-card-actions">
            <span className="gh-muted">{t('선택 {0}개', selected.length)}</span>
            <button type="button" className="btn" disabled={busy || selected.length === 0} onClick={() => void preview()}><Eye size={14} aria-hidden="true" /> {t('미리보기')}</button>
            {canManage && plan && (
              <button type="button" className="btn btn-primary" disabled={busy || !plan.applicable} onClick={() => apply(plan)}>
                <Download size={14} aria-hidden="true" /> {t('가져오기 확정')}
              </button>
            )}
          </div>
          {!canManage && <p className="gh-muted source-readonly">{t('미리보기만 할 수 있습니다. 확정은 이 프로젝트의 관리자 또는 ADMIN 만 할 수 있습니다.')}</p>}
        </>
      )}
      {outcome && <p role={outcome.ok ? 'status' : 'alert'} className={outcome.ok ? 'source-import-ok' : 'source-warn'}>{outcome.message}</p>}
      {plan && <PlanView plan={plan} />}
    </section>
  );
}

function PlanView({ plan }: { plan: Plan }) {
  return (
    <div className="source-import-plan">
      <h4>{t('적용 계획')}</h4>
      <p>
        {plan.applicable ? <Label variant="success">{t('적용 가능')}</Label> : <Label variant="danger">{t('적용할 수 없는 항목이 있습니다')}</Label>}{' '}
        <span className="gh-muted">{countsText(plan.counts)}</span>
      </p>
      {plan.tables.map((table) => (
        <div key={table.key} className="source-table-wrap">
          <p className="gh-mono source-break">{table.sourceTableName} → {table.modelSchemaName}.{table.modelTableName}{table.create ? ` (${t('새로 만들기')})` : ''}</p>
          <table className="source-table">
            <thead><tr><th>{t('항목')}</th><th>{t('현재 모델')}</th><th>{t('적용 후')}</th><th>{t('결과')}</th></tr></thead>
            <tbody>
              {table.items.map((item) => (
                <tr key={item.id}>
                  <td>{itemLabel(item)}</td>
                  <td><Value value={item.before} /></td>
                  <td><Value value={item.after} /></td>
                  <td>
                    {item.selectable ? <Label variant="success">{t('적용')}</Label> : <Label variant="danger">{t('적용 불가')}</Label>}
                    <JudgementLabel item={item} />
                    {item.reason && <div className="gh-muted source-sub">{REASON[item.reason]}{item.reason === 'MISSING_DEPENDENCY' ? ` : ${item.dependsOn.join(', ')}` : ''}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

/** 비교 화면 아래 : 가져오기 이력 (실행마다 모델 변경 이력) */
export function SourceImportHistory() {
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const executions = useQuery({
    queryKey: [IMPORT_KEY, 'executions', page],
    queryFn: () => api<{ rows: Execution[]; total: number }>(`/api/sourceImport/executions?page=${page}&size=10`, { silent: true }),
  });
  return (
    <section className="source-card" aria-label={t('가져오기 이력')}>
      <h3>{t('가져오기 이력')}</h3>
      {executions.isError ? <p role="alert">{executions.error.message}</p>
        : !executions.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : executions.data.rows.length === 0 ? <p className="gh-muted">{t('가져오기 이력이 없습니다.')}</p>
        : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>#</th><th>{t('적용 시각')}</th><th>{t('실행자')}</th><th>{t('수집 시점')}</th><th>{t('내용')}</th><th>{t('작업')}</th></tr></thead>
                <tbody>
                  {executions.data.rows.map((e) => (
                    <tr key={e.id}>
                      <td>{e.id}</td>
                      <td>{formatDateTime(e.appliedAt)}</td>
                      <td>{e.userId ?? '-'}</td>
                      <td>{t('반영 순서 {0}', e.reflectedOrder)}</td>
                      <td className="source-break">
                        {e.summary.counts ? countsText(e.summary.counts) : '-'}
                        {e.summary.tables && <div className="gh-muted source-sub">{e.summary.tables.map((x) => `${x.sourceTable} → ${x.modelTable}`).join(', ')}</div>}
                        {e.summary.modelVersionName && <div className="gh-muted source-sub">{t('직전 모델 버전 : {0}', e.summary.modelVersionName)}</div>}
                      </td>
                      <td><button type="button" className="btn btn-sm" onClick={() => setOpen(open === e.id ? null : e.id)}>{open === e.id ? t('닫기') : t('변경 내용')}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} size={10} total={executions.data.total} onPage={setPage} />
            {open != null && <ExecutionChanges id={open} />}
          </>
        )}
    </section>
  );
}

function ExecutionChanges({ id }: { id: number }) {
  const detail = useQuery({
    queryKey: [IMPORT_KEY, 'execution', id],
    queryFn: () => api<{ changes: Change[] }>(`/api/sourceImport/executions/${id}`, { silent: true }),
  });
  if (detail.isError) return <p role="alert">{detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  return (
    <div className="source-table-wrap">
      <table className="source-table">
        <thead><tr><th>{t('대상')}</th><th>{t('작업')}</th><th>{t('출처')}</th><th>{t('변경 후')}</th></tr></thead>
        <tbody>
          {detail.data.changes.map((c, i) => (
            <tr key={i}>
              <td><Label>{targetTypeLabel(c.targetType)}</Label> <span className="gh-mono source-break">{c.targetName ?? c.targetId}</span></td>
              <td>{actionLabel(c.action)}</td>
              <td>{sourceLabel(c.source)}</td>
              <td className="gh-mono source-break source-sub">{c.after ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
