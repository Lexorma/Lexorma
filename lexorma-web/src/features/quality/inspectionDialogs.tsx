import { useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { useToast } from '../../components/toast/ToastProvider';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t, tc, tv } from '../../lib/i18n';
import { useTableDetail } from '../table/tableDetail';
import { ColumnSuggestionDetail, DetailList, SeverityLabel, useTermLink } from './qualityDialogs';
import {
  OUTCOMES, RULES, endReasonLabel, lastChangeLabel, ruleInfo, ruleLabel, runErrorText, runOutcome, targetTypeLabel,
  type DomainDuplicateGroup, type FindingDetail, type Run, type RunChange,
} from './model';

export { SeverityLabel };

/** 저장 품질 검사 조회의 캐시 키 : 프로젝트마다 나눈다 (프로젝트를 바꾸면 이전 프로젝트 응답이 섞이지 않는다). 대시보드 카드와 같은 키를 쓴다 */
export function inspectionKey(projectId: number, ...rest: (string | number)[]) {
  return ['quality-inspection', projectId, ...rest];
}

/** 두 시각 사이 (끝나지 않았으면 null) */
export function duration(from: string | null, to: string | null): string | null {
  if (!from || !to) return null;
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (!Number.isFinite(ms) || ms < 0) return null;
  if (ms < 1000) return t('{0}ms', ms);
  const seconds = Math.round(ms / 100) / 10;
  return seconds < 60 ? t('{0}초', seconds) : t('{0}분 {1}초', Math.floor(seconds / 60), Math.round(seconds % 60));
}

/** 목록의 진단 내용 : 규칙 설명 + 현재 값 → 기대 값 (서버 문구가 아니라 규칙 카탈로그로 만든다) */
export function findingMessage(rule: string, actual: string | null | undefined, expected: string | null | undefined): ReactNode {
  const description = ruleInfo(rule)?.description ?? t('알 수 없는 규칙의 결과입니다.');
  if (!actual && !expected) return description;
  return (
    <>
      {description}{' '}
      <Muted>
        {actual ? <Mono>{actual}</Mono> : '-'}{expected ? <> → <Mono>{expected}</Mono></> : null}
      </Muted>
    </>
  );
}

// ---------------------------------------------------------------- 근거 표시

/** 근거의 알려진 키 → 화면 이름 (모르는 키는 키 이름 그대로 아래에 둔다 — 새 규칙·근거가 생겨도 화면이 깨지지 않는다) */
const EVIDENCE_LABELS: Record<string, string> = {
  termName: t('표준 용어'),
  domainName: t('표준 도메인'),
  attributes: t('다른 항목'),
  abbreviation: t('영문 약어'),
  missing: t('누락 항목'),
  kind: t('이름 종류'),
  dbType: t('기준 DBMS'),
  ddlName: t('DDL 이름'),
  length: t('길이'),
  maxLength: t('최대 길이'),
  related: t('겹치는 이름'),
  schemaName: t('스키마'),
  tableName: t('테이블'),
  columnName: t('컬럼'),
  groupKey: t('묶은 값'),
  basis: t('판정 기준'),
  tokens: t('약어 판정'),
  prefix: t('프로젝트 접두어'),
  domains: t('도메인 수'),
  sameDomain: t('같은 도메인'),
  orderDiffers: t('단어 순서 다름'),
  normalizedName: t('정규화한 이름'),
  synonym: t('이음동의어'),
  replaced: t('바꾼 단어'),
  boundary: t('금칙어 위치'),
  matches: t('일치한 금칙어'),
};

/** 내부 ID 만 담은 근거 키 : 사용자에게 보여 주지 않는다 (구성원은 위 그룹의 용어 목록이 이름으로 보여 준다) */
const HIDDEN_EVIDENCE = new Set(['canonicalWordIds', 'wordIds', 'synonymOwnerTermId']);
const KIND_TEXT: Record<string, string> = {
  SCHEMA: t('스키마'), TABLE: t('테이블'), COLUMN: t('컬럼'), PRIMARY_KEY: 'PK', UNIQUE: 'UNIQUE', FOREIGN_KEY: 'FK', CHECK: 'CHECK', INDEX: t('인덱스'),
  TERM: t('표준 용어'), WORD: t('표준 단어'),
};
const BOUNDARY_TEXT: Record<string, string> = { TERM_WORD: t('용어의 구성 단어'), TERM_NAME: t('용어명') };

const MISSING_LABELS: Record<string, string> = { COMMENT: t('코멘트'), BIZ_DESCRIPTION: t('업무 설명') };
const ATTRIBUTE_TEXT: Record<string, string> = { DATA_TYPE: t('데이터 타입'), LENGTH: t('길이'), PRECISION: t('정밀도'), SCALE: t('소수 자릿수') };
const TOKEN_TEXT: Record<string, string> = { APPROVED: t('승인'), NOT_APPROVED: t('미승인 단어'), NOT_FOUND: t('미등록'), EMPTY: t('빈 약어') };

function evidenceValue(key: string, value: unknown): ReactNode {
  if (value == null || value === '') return <Muted>-</Muted>;
  if (key === 'missing') return MISSING_LABELS[String(value)] ?? String(value);
  if (key === 'kind') return KIND_TEXT[String(value)] ?? String(value);
  if (key === 'boundary') return BOUNDARY_TEXT[String(value)] ?? String(value);
  // 판정 기준 : 서버가 판정 때 쓴 기준 문구(한국어 원문)를 표시 언어로 (번역 사전에 없으면 원문)
  if (key === 'basis') return tv(String(value));
  if (key === 'related' && Array.isArray(value)) {
    return (value as { ddlName?: string; kind?: string; tableName?: string }[])
      .map((o, i) => <div key={i}><Mono>{o.ddlName ?? '-'}</Mono> ({KIND_TEXT[o.kind ?? ''] ?? o.kind}, {o.tableName ?? '-'})</div>);
  }
  if (value === 'true' || value === true) return t('예');
  if (value === 'false' || value === false) return t('아니오');
  if (key === 'attributes') return String(value).split(',').map((a) => ATTRIBUTE_TEXT[a] ?? a).join(', ');
  if (key === 'tokens' && Array.isArray(value)) {
    return (
      <span className="quality-chips">
        {value.map((token) => {
          const [name, status] = String(token).split(':');
          return <Label key={String(token)} variant={status === 'APPROVED' ? 'success' : 'attention'}><span className="gh-mono">{name}</span> · {TOKEN_TEXT[status] ?? status}</Label>;
        })}
      </span>
    );
  }
  if (typeof value === 'object') return <code className="quality-json">{JSON.stringify(value)}</code>;
  return ['ddlName', 'related', 'schemaName', 'tableName', 'columnName', 'groupKey', 'abbreviation'].includes(key) ? <Mono>{String(value)}</Mono> : String(value);
}

interface Member {
  termId?: number;
  termName?: string | null;
  abbreviation?: string | null;
  note?: string | null;
}

function Evidence({ detail, onTerm }: { detail: FindingDetail; onTerm?: (termName: string) => void }) {
  const evidence = (detail.evidence?.evidence ?? {}) as Record<string, unknown>;
  const details = (detail.evidence?.details ?? {}) as Record<string, unknown>;
  const members = Array.isArray(details.members) ? (details.members as Member[]) : [];
  const entries: [string, unknown][] = [
    ...Object.entries(details).filter(([key]) => key !== 'members'),
    ...Object.entries(evidence).filter(([key]) => !HIDDEN_EVIDENCE.has(key)),
  ];
  return (
    <>
      <dl className="dict-detail">
        {(detail.finding.actualValue || detail.finding.expectedValue) && (
          <>
            <dt>{t('현재 값')}</dt><dd>{detail.finding.actualValue ? <Mono>{detail.finding.actualValue}</Mono> : <Muted>-</Muted>}</dd>
            <dt>{t('기대 값')}</dt><dd>{detail.finding.expectedValue ? <Mono>{detail.finding.expectedValue}</Mono> : <Muted>-</Muted>}</dd>
          </>
        )}
        {entries.map(([key, value]) => [
          <dt key={`t-${key}`}>{EVIDENCE_LABELS[key] ?? key}</dt>,
          <dd key={`d-${key}`}>{evidenceValue(key, value)}</dd>,
        ])}
      </dl>
      {members.length > 0 && (
        <>
          <h4 className="quality-subtitle">{t('그룹의 용어 ({0})', members.length)}</h4>
          <ul className="quality-members">
            {members.map((m, i) => (
              <li key={m.termId ?? i}>
                {onTerm && m.termName ? <button type="button" className="quality-link" onClick={() => onTerm(m.termName!)}>{m.termName}</button> : (m.termName ?? '-')}
                {m.abbreviation && <> · <Mono>{m.abbreviation}</Mono></>}
                {m.note && <Muted> — {m.note}</Muted>}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------- 결과 상세 · 예외 처리

const HISTORY_LABELS = { EXCLUDE: t('예외 처리'), UNEXCLUDE: t('예외 해제'), REOPEN: t('재발') } as const;

/**
 * 결과 1건 상세 : 규칙·대상·근거·발견/확인/해결 시각·마지막 실행·예외와 이력. 대상이 지금도 있으면 기존 상세 화면으로 이동한다 (삭제됐으면 근거만 남는다).
 * 예외 처리·해제는 사유가 필요하고 화면이 읽은 version 을 보낸다 — 다른 사용자가 먼저 바꿨으면(409) 입력한 사유를 그대로 두고 최신 상태를 보여 준 뒤,
 * 사용자가 다시 누를 때만 보낸다 (자동 재시도·덮어쓰기 없음, AI6-3-10).
 */
export function FindingDetailView({ projectId, findingId, close, onChanged, onRun }: {
  projectId: number; findingId: number; close: () => void; onChanged: () => void; onRun: (runId: number) => void;
}) {
  const { canView } = useAuth();
  const dialog = useDialog();
  const toast = useToast();
  const queryClient = useQueryClient();
  const termLink = useTermLink();
  const { openTableDetailById } = useTableDetail();
  const query = useQuery({
    queryKey: inspectionKey(projectId, 'finding', findingId),
    queryFn: () => api<FindingDetail>(`/api/qualityInspection/findings/${enc(findingId)}`),
    staleTime: 0,
  });
  const [mode, setMode] = useState<'exclude' | 'unexclude' | null>(null);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [sending, setSending] = useState(false);

  if (query.isPending) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (query.isError) return <p className="gh-muted" role="alert">{t('결과를 불러오지 못했습니다.')}</p>;
  const detail = query.data;
  const f = detail.finding;
  const canExclude = canView('quality-inspection-exception');
  const info = ruleInfo(f.rule);

  const submit = async () => {
    if (!mode) return;
    if (!reason.trim()) {
      setProblem(t('사유를 입력해주세요.'));
      return;
    }
    setSending(true);
    setProblem(null);
    try {
      await api<FindingDetail>(`/api/qualityInspection/findings/${enc(findingId)}/${mode}`, {
        method: 'POST', body: { reason, version: f.version }, conflict: 'handled',
      });
      setMode(null);
      setReason('');
      setConflict(false);
      await query.refetch();
      onChanged();
      void queryClient.invalidateQueries({ queryKey: inspectionKey(projectId, 'summary') });
      toast.success(mode === 'exclude' ? t('예외로 처리했습니다.') : t('예외를 해제했습니다.'));
    } catch (error) {
      if (error instanceof ApiError && error.code === 'INSPECTION_RESULT_CONFLICT') {
        // 입력한 사유는 그대로 두고 최신 상태를 다시 읽는다
        setConflict(true);
        await query.refetch();
        onChanged();
      } else if (error instanceof ApiError && (error.code === 'INSPECTION_REASON_REQUIRED' || error.code === 'INSPECTION_REASON_TOO_LONG')) {
        setProblem(error.message);
      } else if (error instanceof ApiError && error.code === 'INSPECTION_RESULT_NOT_OPEN') {
        setConflict(true);
        await query.refetch();
      }
    } finally {
      setSending(false);
    }
  };

  // 처리·해제를 새로 시작하거나 취소하면 입력을 비운다 (사유는 충돌 뒤 같은 요청을 다시 보낼 때만 남긴다 — 다른 요청에 섞이지 않게)
  const start = (next: 'exclude' | 'unexclude' | null) => {
    setMode(next);
    setReason('');
    setConflict(false);
    setProblem(null);
  };

  // 충돌 뒤 최신 상태에서 이 요청이 아직 뜻이 있는지 (이미 같은 상태면 다시 보낼 것이 없다)
  const stillApplicable = mode === 'exclude' ? !f.excluded && f.status === 'OPEN' : mode === 'unexclude' ? f.excluded : false;

  const openTarget = () => {
    if (f.tableId == null) return;
    close();
    void openTableDetailById(f.tableId, { columnId: f.targetType === 'COLUMN' ? f.targetId : undefined });
  };
  const suggestions = () => {
    dialog.open({
      title: t('컬럼명 개선 후보'),
      className: 'dialog-wide',
      content: ({ close: closeInner }: DialogControls) => <ColumnSuggestionDetail columnId={f.targetId} close={closeInner} />,
    });
  };
  const tableTarget = (f.targetType === 'TABLE' || f.targetType === 'COLUMN') && f.tableId != null;

  return (
    <div className="quality-finding">
      <DetailList items={[
        [t('진단 규칙'), info?.label ?? t('알 수 없는 규칙')],
        [t('대상 유형'), targetTypeLabel(f.targetType)],
        [t('대상'), f.targetName, true],
      ]} />
      <p className="quality-finding-head">
        <SeverityLabel severity={f.severity} />{' '}
        {f.status === 'OPEN' ? <Label variant="attention">{t('미해결')}</Label> : <Label variant="success">{t('해결')}</Label>}{' '}
        {f.excluded && <Label variant="done">{t('예외')}</Label>}{' '}
        {!f.ruleActive && <Label>{t('꺼진 규칙')}</Label>}{' '}
        {!detail.targetExists && <Label variant="danger">{t('대상 삭제됨')}</Label>}
      </p>
      <p>{info?.description}</p>

      <h4 className="quality-subtitle">{t('근거')}</h4>
      <Evidence detail={detail} onTerm={termLink ? (name) => termLink(name, close) : undefined} />

      <h4 className="quality-subtitle">{t('검사 이력')}</h4>
      <dl className="dict-detail">
        <dt>{t('최초 발견')}</dt><dd>{formatDateTime(f.firstSeenAt)} <Muted>(#{f.firstRunId})</Muted></dd>
        <dt>{t('최종 확인')}</dt>
        <dd>{formatDateTime(f.lastSeenAt)} <button type="button" className="quality-link" onClick={() => { close(); onRun(f.lastRunId); }}>#{f.lastRunId}</button> · {t('마지막 변화')} {lastChangeLabel(f.lastChange)}</dd>
        <dt>{t('해결')}</dt><dd>{f.status === 'RESOLVED' ? `${formatDateTime(f.endedAt)} · ${endReasonLabel(f.endReason)}` : <Muted>-</Muted>}</dd>
      </dl>

      <h4 className="quality-subtitle">{t('예외·재발 이력')}</h4>
      {detail.history.length === 0 ? <p className="gh-muted">{t('예외 처리·해제나 재발 기록이 없습니다.')}</p> : (
        <ul className="quality-history">
          {detail.history.map((h) => (
            <li key={h.id}>
              <Label variant={h.type === 'REOPEN' ? 'attention' : 'done'}>{HISTORY_LABELS[h.type]}</Label>{' '}
              {formatDateTime(h.createdAt)} · {h.createdBy ?? '-'}
              {h.reason && <div className="quality-history-reason">{h.reason}</div>}
              {h.type === 'REOPEN' && <Muted> {t('직전 해결 {0} ({1})', formatDateTime(h.endedAt), endReasonLabel(h.endReason))}</Muted>}
            </li>
          ))}
        </ul>
      )}

      {mode && (
        <div className="quality-exclusion" role="group" aria-label={mode === 'exclude' ? t('예외 처리') : t('예외 해제')}>
          <p className="form-help">
            {mode === 'exclude'
              ? t('예외 처리는 문제를 해결하는 것이 아니라 이 결과를 기본 목록·요약에서 빼 두는 표시입니다. 다음 검사에서도 예외는 그대로 유지됩니다.')
              : t('예외를 해제하면 이 결과가 다시 기본 목록·요약에 나옵니다. 실제 문제 상태는 바뀌지 않습니다.')}
          </p>
          {conflict && (
            <div className="quality-notice is-attention" role="alert">
              {t('다른 사용자가 그사이 이 결과를 바꿨습니다. 아래는 최신 상태입니다 — 입력한 사유는 그대로 두었습니다.')}{' '}
              {t('지금 상태 : {0}, {1}.', f.status === 'OPEN' ? t('미해결') : t('해결'), f.excluded ? t('예외') : t('예외 아님'))}{' '}
              {!stillApplicable && t('이 요청은 더 이상 필요하지 않습니다.')}
            </div>
          )}
          <label className="form-label" htmlFor="quality-exclusion-reason">{t('사유')}</label>
          <textarea id="quality-exclusion-reason" className="form-control" rows={3} maxLength={1000} value={reason}
            onChange={(e) => { setReason(e.target.value); setProblem(null); }} />
          {problem && <p className="quality-error" role="alert">{problem}</p>}
          <div className="gh-dialog-actions">
            <button type="button" className="btn btn-primary" disabled={sending || (conflict && !stillApplicable)} onClick={() => void submit()}>
              {conflict ? t('최신 상태로 다시 요청') : mode === 'exclude' ? t('예외 처리') : t('예외 해제')}
            </button>
            <button type="button" className="btn" disabled={sending} onClick={() => start(null)}>{t('취소')}</button>
          </div>
        </div>
      )}

      <div className="gh-dialog-actions">
        {tableTarget && canView('table-manage') && (
          <button type="button" className="btn" disabled={!detail.targetExists} onClick={openTarget}
            title={detail.targetExists ? undefined : t('대상이 삭제되어 이동할 수 없습니다.')}>{t('테이블 상세')}</button>
        )}
        {f.targetType === 'COLUMN' && detail.targetExists && (f.rule === 'TERM_NOT_LINKED' || f.rule === 'COLUMN_NAME_MISMATCH') && (
          <button type="button" className="btn" onClick={suggestions}>{t('개선 후보')}</button>
        )}
        {canExclude && !mode && !f.excluded && f.status === 'OPEN' && (
          <button type="button" className="btn" onClick={() => start('exclude')}>{t('예외 처리')}</button>
        )}
        {canExclude && !mode && f.excluded && (
          <button type="button" className="btn" onClick={() => start('unexclude')}>{t('예외 해제')}</button>
        )}
        <button type="button" className="btn" onClick={close}>{t('닫기')}</button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- 실행 상세

const CHANGE_COLUMNS: [RunChange, string][] = [
  ['NEW', t('신규')], ['PERSISTING', tc('마지막 변화', '유지')], ['REOPENED', t('재발')], ['RULE_SATISFIED', t('실제 개선')], ['TARGET_DELETED', t('대상 삭제')], ['EXCLUDED', t('예외')],
];

/**
 * 실행 1회 : 상태·사유·시각, 그 실행에 적용한 규칙(판정 상태·규칙별 건수)과 적용하지 않은 규칙. 판정하지 못한 규칙은 0건이 아니라 '판정하지 못함'.
 * 건수는 그 실행이 결과를 확정한 시점의 값이다. 과거 실행 당시의 전체 결과 목록은 저장하지 않아 보여 주지 않는다.
 */
export function RunDetailView({ projectId, runId, close }: { projectId: number; runId: number; close: () => void }) {
  const query = useQuery({
    queryKey: inspectionKey(projectId, 'run', runId),
    queryFn: () => api<Run>(`/api/qualityInspection/runs/${enc(runId)}`),
    refetchInterval: (q) => (q.state.data && (q.state.data.status === 'PENDING' || q.state.data.status === 'RUNNING') ? 2000 : false),
  });
  if (query.isPending) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (query.isError) return <p className="gh-muted" role="alert">{t('실행 정보를 불러오지 못했습니다.')}</p>;
  const run = query.data;
  const outcome = OUTCOMES[runOutcome(run)];
  const applied = new Set(run.rules.map((r) => r.rule));
  const notApplied = run.status === 'PENDING' ? [] : RULES.filter((r) => !applied.has(r.rule));
  return (
    <div className="quality-run">
      <p><Label variant={outcome.variant} dot>{outcome.label}</Label> {runErrorText(run)}</p>
      <dl className="dict-detail">
        <dt>{t('요청')}</dt><dd>{formatDateTime(run.requestedAt)} · {run.requestedBy ?? '-'}</dd>
        <dt>{t('시작')}</dt><dd>{run.startedAt ? formatDateTime(run.startedAt) : <Muted>-</Muted>}</dd>
        <dt>{t('검사 기준 시각')}</dt><dd>{run.basisAt ? formatDateTime(run.basisAt) : <Muted>-</Muted>}</dd>
        <dt>{t('완료 시각')}</dt><dd>{run.endedAt ? formatDateTime(run.endedAt) : <Muted>-</Muted>} {duration(run.startedAt ?? run.requestedAt, run.endedAt) && <Muted>({duration(run.startedAt ?? run.requestedAt, run.endedAt)})</Muted>}</dd>
        <dt>{t('적용 규칙')}</dt><dd>{run.appliedRuleCount}{run.failedRules.length > 0 && <> · <Label variant="attention">{t('판정하지 못한 규칙 {0}개', run.failedRules.length)}</Label></>}</dd>
        <dt>{t('해결')}</dt><dd>{t('실제 개선 {0} · 대상 삭제 {1}', run.totals.RULE_SATISFIED, run.totals.TARGET_DELETED)}</dd>
      </dl>
      {run.rules.length > 0 && (
        <div className="table-responsive">
          <table className="table quality-run-table">
            <thead>
              <tr>
                <th scope="col">{t('진단 규칙')}</th>
                <th scope="col">{t('판정')}</th>
                <th scope="col">{t('발견')}</th>
                {CHANGE_COLUMNS.map(([key, label]) => <th key={key} scope="col">{label}</th>)}
              </tr>
            </thead>
            <tbody>
              {run.rules.map((r) => (
                <tr key={r.rule}>
                  <td>{ruleLabel(r.rule)}</td>
                  <td>{r.status === 'COMPLETED' ? <Label variant="success">{t('판정함')}</Label>
                    : r.status === 'FAILED' ? <Label variant="danger">{t('판정하지 못함')}</Label> : <Label>{t('판정 중')}</Label>}</td>
                  {r.status === 'COMPLETED'
                    ? <><td>{r.resultCount ?? 0}</td>{CHANGE_COLUMNS.map(([key]) => <td key={key}>{r.counts[key]}</td>)}</>
                    : <td colSpan={CHANGE_COLUMNS.length + 1}><Muted>{r.status === 'FAILED' ? t('이 규칙의 결과는 이전 검사 그대로입니다.') : '-'}</Muted></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {notApplied.length > 0 && (
        <p className="form-help">{t('검사 당시 꺼져 있던 규칙 : {0}', notApplied.map((r) => r.label).join(', '))}</p>
      )}
      <p className="form-help">{t('건수는 이 실행이 결과를 확정한 시점의 값입니다. 그 뒤의 예외 처리·해제와 규칙 설정 변경으로 바뀌지 않습니다. 실행 당시의 전체 결과 목록은 저장하지 않습니다.')}</p>
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={close}>{t('닫기')}</button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- 표준 도메인 이름 중복 ([DICT-2], 규칙이 아닌 보조 보고)

/** 같은 이름(대소문자 무시)의 표준 도메인이 있을 때만 알린다. 저장 검사의 규칙이 아니라 기존 보조 조회다 — 자동으로 정리하지 않는다 */
export function DomainDuplicatesNotice() {
  const { user } = useAuth();
  const dialog = useDialog();
  const query = useQuery({
    queryKey: ['standardization-quality-domain-duplicates', user?.projectId],
    queryFn: () => api<{ groups: DomainDuplicateGroup[] }>('/api/standardizationQuality/domains/duplicates', { silent: true }),
  });
  const groups = query.data?.groups ?? [];
  if (groups.length === 0) return null;
  const open = () => dialog.open({
    title: t('표준 도메인 이름 중복 (대소문자 무시)'),
    className: 'dialog-wide',
    content: ({ close }: DialogControls) => (
      <>
        <p className="gh-muted">{t('같은 이름의 표준 도메인이 {0}묶음 있습니다. 이 이름을 쓰는 표준 용어는 등록·수정할 수 없습니다. 자동으로 정리하지 않으니 표준 도메인 화면에서 확인해주세요.', groups.length)}</p>
        <ul className="quality-candidates">
          {groups.map((group) => (
            <li key={group.normalizedName} className="quality-candidate">
              <div className="quality-candidate-head"><Mono>{group.normalizedName}</Mono><Muted>{t('도메인 {0}개', group.count)}</Muted></div>
              <span className="quality-chips">
                {group.members.map((member) => (
                  <Label key={member.domainId} variant={member.isApproval ? '' : 'attention'}>
                    {member.domainName} · #{member.domainId} · {member.isApproval ? t('승인') : t('미승인')} · {t('용어 {0} · 컬럼 {1}', member.termCount, member.columnCount)}
                  </Label>
                ))}
              </span>
            </li>
          ))}
        </ul>
        <div className="gh-dialog-actions"><button type="button" className="btn" onClick={close}>{t('닫기')}</button></div>
      </>
    ),
  });
  return (
    <p className="quality-notice" role="status">
      {t('같은 이름의 표준 도메인이 {0}묶음 있습니다 (검사 규칙과 별도인 보고).', groups.length)}{' '}
      <button type="button" className="quality-link" onClick={open}>{t('자세히')}</button>
    </p>
  );
}
