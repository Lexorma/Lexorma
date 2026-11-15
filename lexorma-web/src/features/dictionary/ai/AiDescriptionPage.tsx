import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { RefreshCw, Sparkles } from 'lucide-react';
import { useDialog } from '../../../components/dialog/DialogProvider';
import { PageHeader } from '../../../components/layout/PageHeader';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { aiDictionary, CERTAINTY_LABEL, JOB_LABEL, STATUS_CLASS, STATUS_LABEL, type JobSummary, type ProposalSummary, type TargetRow } from './aiDictionary';
import { AiStatusNotice } from './AiStatusNotice';
import { ProposalDetailView } from './ProposalDetailView';
import { SelectAll } from './SelectAll';
import '../../../styles/ai-dictionary.css';

const FILTERS = ['OPEN', 'PROPOSED', 'REVIEWED', 'REVIEW_REQUIRED', 'ON_HOLD', 'APPROVED', 'REJECTED', 'SUPERSEDED', ''] as const;

/**
 * 데이터 사전 › AI 설명 제안 (AI-5). 대상 선택 → 초안 생성(단건·대량) → 검토 목록 → 상세에서 승인·수정 후 승인·반려·보류.
 * AI 제안은 승인 전까지 업무 설명을 바꾸지 않는다. 버튼은 권한(생성 USER, 검토 = 승인 권한)에 따라 보이고, 서버가 다시 확인한다.
 * 주소 : ?proposal=ID (그 제안을 연다) · ?tableId=&columnId= (그 대상의 제안으로 이동 — 테이블·컬럼 화면과 ERD 에서 온다) · 예전 ?tab=evaluation (품질 평가) 은 /aiEvaluation 으로 옮긴다 (App 라우트)
 */
export default function AiDescriptionPage() {
  const { canView, approvalAvailable } = useAuth();
  const canGenerate = canView('ai-dictionary-generate');
  const canReview = canView('ai-dictionary-review') && approvalAvailable;
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const initialTable = params.get('tableId') ? Number(params.get('tableId')) : null;
  const initialColumn = params.get('columnId') ? Number(params.get('columnId')) : null;

  const [tableId, setTableId] = useState<number | null>(initialTable);
  const [onlyMissing, setOnlyMissing] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null);
  const [filter, setFilter] = useState({ status: initialTable ? '' : 'OPEN', keyword: '', recheck: false, page: 0, tableId: initialTable });
  const [checked, setChecked] = useState<Map<number, number>>(new Map());

  const tables = useQuery({ queryKey: ['ai-dict-targets', null, false], queryFn: () => aiDictionary.targets(null, false) });
  const targets = useQuery({ queryKey: ['ai-dict-targets', tableId, onlyMissing], queryFn: () => aiDictionary.targets(tableId, onlyMissing) });
  const list = useQuery({ queryKey: ['ai-dict-proposals', filter], queryFn: () => aiDictionary.list(filter) });
  const jobs = useQuery({
    queryKey: ['ai-dict-jobs'], queryFn: aiDictionary.jobs,
    refetchInterval: (q) => ((q.state.data as JobSummary[] | undefined)?.some((j) => j.status === 'PENDING' || j.status === 'RUNNING') ? 2000 : false),
  });
  const active = jobs.data?.find((j) => j.status === 'PENDING' || j.status === 'RUNNING');
  const wasActive = useRef(false);
  useEffect(() => {
    // 실행이 끝나면 목록을 새로 본다
    if (wasActive.current && !active) refresh();
    wasActive.current = !!active;
  }, [active]); // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['ai-dict-proposals'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-dict-targets'] });
  };

  const openDetail = (id: number) => {
    const next = new URLSearchParams(params);
    next.set('proposal', String(id));
    setParams(next, { replace: true });
    dialog.open({
      title: t('AI 설명 제안'),
      className: 'dialog-wide',
      onClose: () => {
        const after = new URLSearchParams(window.location.search);
        after.delete('proposal');
        setParams(after, { replace: true });
      },
      content: ({ close }) => <ProposalDetailView id={id} onChanged={refresh} onRegenerated={(newId) => { close(); openDetail(newId); }} />,
    });
  };

  // 주소로 온 제안·대상 열기 (한 번)
  const opened = useRef(false);
  useEffect(() => {
    if (opened.current) return;
    const proposal = params.get('proposal');
    if (proposal) {
      opened.current = true;
      openDetail(Number(proposal));
      return;
    }
    if (initialTable && targets.data) {
      opened.current = true;
      const row = targets.data.find((r) => r.tableId === initialTable && (r.columnId ?? null) === initialColumn);
      if (row?.proposalId) openDetail(row.proposalId);
      if (row) setSelected(new Set([key(row)]));
    }
  }, [targets.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const rows = targets.data ?? [];
  const selectedRows = useMemo(() => rows.filter((r) => selected.has(key(r))), [rows, selected]);
  const toggle = (row: TargetRow) => setSelected((s) => {
    const next = new Set(s);
    if (next.has(key(row))) next.delete(key(row));
    else next.add(key(row));
    return next;
  });

  const fail = (e: unknown) => setNotice({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });

  const generateSelected = async () => {
    if (selectedRows.length === 0) return;
    setBusy(true);
    setNotice(null);
    try {
      if (selectedRows.length === 1) {
        const row = selectedRows[0];
        const result = await aiDictionary.generate(row.tableId, row.columnId, false);
        setNotice(result.outcome === 'SKIPPED'
          ? { kind: 'warn', text: t('검토 대기 중인 제안이 있어 새로 만들지 않았습니다. 다시 만들려면 상세에서 다시 만드세요.') }
          : { kind: 'ok', text: t('제안을 만들었습니다. 승인 전까지 업무 설명은 바뀌지 않습니다.') });
        refresh();
        openDetail(result.proposal.id);
      } else {
        await aiDictionary.startJob({ scope: 'SELECTED', targets: selectedRows.map((r) => ({ tableId: r.tableId, columnId: r.columnId })) });
        setNotice({ kind: 'ok', text: t('{0}개 대상의 제안을 차례로 만듭니다. 진행 상태는 아래에서 볼 수 있습니다.', selectedRows.length) });
        void jobs.refetch();
      }
      setSelected(new Set());
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const startJob = async (scope: 'MISSING_TABLES' | 'TABLE_COLUMNS') => {
    setBusy(true);
    setNotice(null);
    try {
      const job = await aiDictionary.startJob(scope === 'MISSING_TABLES' ? { scope } : { scope, tableId, onlyMissing: true });
      setNotice({ kind: 'ok', text: t('{0}개 대상의 제안을 차례로 만듭니다. 진행 상태는 아래에서 볼 수 있습니다.', job.targetCount) });
      void jobs.refetch();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const recheck = async () => {
    setBusy(true);
    setNotice(null);
    try {
      const r = await aiDictionary.recheck();
      setNotice({ kind: r.reviewRequired + r.recheckFlagged > 0 ? 'warn' : 'ok', text: t('{0}개를 확인했습니다. 재검토 필요 {1}개, 승인 뒤 근거 변경 {2}개.', r.checked, r.reviewRequired, r.recheckFlagged) });
      refresh();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const bulk = (action: 'APPROVE' | 'REJECT' | 'HOLD') => {
    const items = [...checked.entries()].map(([id, version]) => ({ id, version }));
    if (items.length === 0) return;
    const perform = async (reason: string | null) => {
      setBusy(true);
      setNotice(null);
      try {
        const results = await aiDictionary.bulk(action, items, reason);
        const ok = results.filter((r) => r.outcome !== 'FAILED' && r.outcome !== 'REVIEW_REQUIRED').length;
        const review = results.filter((r) => r.outcome === 'REVIEW_REQUIRED').length;
        const failed = results.filter((r) => r.outcome === 'FAILED').length;
        setNotice({ kind: failed + review > 0 ? 'warn' : 'ok', text: t('{0}개 처리, 재검토 필요 {1}개, 실패 {2}개 (다른 사용자가 먼저 처리했거나 상태가 바뀐 제안).', ok, review, failed) });
        setChecked(new Map());
        refresh();
      } catch (e) {
        fail(e);
      } finally {
        setBusy(false);
      }
    };
    if (action === 'APPROVE') {
      dialog.confirm(t('고른 {0}개 제안을 그대로 승인할까요? 승인하면 업무 설명에 반영됩니다 (근거가 바뀐 제안은 반영하지 않습니다).', items.length), () => void perform(null));
      return;
    }
    dialog.open({
      title: action === 'REJECT' ? t('일괄 반려') : t('일괄 보류'),
      content: ({ close }) => <ReasonForm required={action === 'REJECT'} onSubmit={(reason) => { close(); void perform(reason); }} />,
    });
  };

  const tableOptions = (tables.data ?? []).filter((r) => r.targetType === 'TABLE');
  const page = list.data;

  return (
    <div className="ai-dict-page">
      <PageHeader category={t('AI 관리')} title={t('AI 설명 제안')}
                  description={t('AI 가 등록된 메타데이터를 근거로 테이블·컬럼의 업무 설명 초안을 만듭니다. 사람이 승인해야 업무 설명에 반영되며, 원본 COMMENT·DDL 설명·표준 용어 연결은 바꾸지 않습니다.')} />

      <AiStatusNotice />
      {notice && <div className={`ai-dict-callout is-${notice.kind}`} role="status">{notice.text}</div>}

      <section className="ai-dict-card">
        <h3>{t('대상 선택')}</h3>
        <div className="ai-dict-toolbar">
          <select className="search-column-select" aria-label={t('테이블')} value={tableId ?? ''} onChange={(e) => { setTableId(e.target.value ? Number(e.target.value) : null); setSelected(new Set()); }}>
            <option value="">{t('테이블 목록')}</option>
            {tableOptions.map((r) => <option key={r.tableId} value={r.tableId}>{r.name}{r.description ? ` (${r.description})` : ''}</option>)}
          </select>
          <label className="ai-dict-check"><input type="checkbox" checked={onlyMissing} onChange={(e) => setOnlyMissing(e.target.checked)} /> {t('업무 설명 없는 것만')}</label>
        </div>
        <div className="ai-dict-table-wrap">
          <table className="ai-dict-table">
            <thead>
              <tr>
                {canGenerate && <th className="ai-dict-col-check">
                  <SelectAll label={t('전체 선택')} total={rows.length} selected={selectedRows.length}
                             onChange={(all) => setSelected(all ? new Set(rows.map(key)) : new Set())} />
                </th>}
                <th>{t('대상')}</th><th>{t('설명')}</th><th>{t('원본 COMMENT')}</th><th>{t('업무 설명')}</th><th>{t('최근 제안')}</th>
              </tr>
            </thead>
            <tbody>
              {targets.isLoading && <tr><td colSpan={6} className="gh-muted">{t('불러오는 중…')}</td></tr>}
              {!targets.isLoading && rows.length === 0 && <tr><td colSpan={6} className="gh-muted">{t('대상이 없습니다.')}</td></tr>}
              {rows.map((r) => (
                <tr key={key(r)}>
                  {canGenerate && <td className="ai-dict-col-check"><input type="checkbox" aria-label={r.name} checked={selected.has(key(r))} onChange={() => toggle(r)} /></td>}
                  <td><span className="gh-label">{r.targetType === 'TABLE' ? t('테이블') : t('컬럼')}</span> <span className="ai-dict-name">{r.name}</span></td>
                  <td>{r.description ?? '-'}</td>
                  <td>{r.hasSourceComment ? t('있음') : <span className="gh-muted">{t('없음')}</span>}</td>
                  <td>{r.hasBizDescription ? t('있음') : <span className="gh-muted">{t('없음')}</span>}</td>
                  <td>
                    {r.proposalStatus ? (
                      <button type="button" className="ai-dict-link" onClick={() => openDetail(r.proposalId!)}>
                        <span className={`gh-label ${STATUS_CLASS[r.proposalStatus]}`}>{STATUS_LABEL[r.proposalStatus]}</span>
                      </button>
                    ) : <span className="gh-muted">-</span>}
                    {r.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {canGenerate && (
          <div className="ai-dict-buttons">
            <button type="button" className="btn btn-primary" disabled={busy || selectedRows.length === 0} onClick={generateSelected}>
              <Sparkles size={14} aria-hidden="true" /> {busy ? t('만드는 중…') : t('선택한 {0}개 초안 만들기', selectedRows.length)}
            </button>
            {tableId != null && <button type="button" className="btn" disabled={busy || !!active} onClick={() => startJob('TABLE_COLUMNS')}>{t('이 테이블의 업무 설명 없는 컬럼 모두')}</button>}
            <button type="button" className="btn" disabled={busy || !!active} onClick={() => startJob('MISSING_TABLES')}>{t('업무 설명 없는 테이블 모두')}</button>
            <button type="button" className="btn" disabled={busy} onClick={recheck}><RefreshCw size={14} aria-hidden="true" /> {t('근거 변경 확인')}</button>
          </div>
        )}
      </section>

      {(jobs.data ?? []).length > 0 && (
        <section className="ai-dict-card">
          <h3>{t('대량 생성')}</h3>
          <ul className="ai-dict-jobs">
            {(jobs.data ?? []).slice(0, 5).map((j) => {
              const done = j.completedCount + j.failedCount + j.skippedCount;
              return (
                <li key={j.id}>
                  <span className={`gh-label ${j.status === 'COMPLETED' ? 'gh-label-success' : j.status === 'RUNNING' || j.status === 'PENDING' ? 'gh-label-accent' : 'gh-label-attention'}`}>{JOB_LABEL[j.status]}</span>
                  <span>#{j.id}</span>
                  <progress max={Math.max(j.targetCount, 1)} value={done} aria-label={t('진행')} />
                  <span className="gh-muted">{t('{0}/{1} · 만듦 {2} · 건너뜀 {3} · 실패 {4}', done, j.targetCount, j.completedCount, j.skippedCount, j.failedCount)}</span>
                  {j.errors.length > 0 && <span className="gh-muted ai-dict-small">{[...new Set(j.errors.map((e) => e.code))].join(', ')}</span>}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      <section className="ai-dict-card">
        <h3>{t('제안 목록')}</h3>
        <div className="ai-dict-toolbar">
          <select className="search-column-select" aria-label={t('검토 상태')} value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value, page: 0 })}>
            {FILTERS.map((s) => <option key={s} value={s}>{s === 'OPEN' ? t('검토 대기 전체') : s === '' ? t('전체') : STATUS_LABEL[s]}</option>)}
          </select>
          <input className="search-input" placeholder={t('대상 이름')} aria-label={t('대상 이름')} value={filter.keyword} onChange={(e) => setFilter({ ...filter, keyword: e.target.value, page: 0 })} />
          <label className="ai-dict-check"><input type="checkbox" checked={filter.recheck} onChange={(e) => setFilter({ ...filter, recheck: e.target.checked, page: 0 })} /> {t('승인 뒤 근거 변경만')}</label>
          {filter.tableId != null && <button type="button" className="btn btn-sm" onClick={() => setFilter({ ...filter, tableId: null, page: 0 })}>{t('테이블 조건 해제')}</button>}
          {canReview && (() => {
            // 이 페이지에서 일괄 처리할 수 있는 제안 (검토 대기·검토 중·보류)
            const reviewable = (page?.content ?? []).filter((p) => p.status === 'PROPOSED' || p.status === 'REVIEWED' || p.status === 'ON_HOLD');
            const picked = reviewable.filter((p) => checked.has(p.id)).length;
            return (
              <label className="ai-dict-check">
                <SelectAll label={t('이 페이지 전체 선택')} total={reviewable.length} selected={picked}
                           onChange={(all) => setChecked((m) => {
                             const next = new Map(m);
                             reviewable.forEach((p) => { if (all) next.set(p.id, p.version); else next.delete(p.id); });
                             return next;
                           })} />
                {t('이 페이지 전체 선택')}
              </label>
            );
          })()}
        </div>
        {canReview && checked.size > 0 && (
          <div className="ai-dict-buttons">
            <span className="gh-muted">{t('{0}개 선택', checked.size)}</span>
            <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => bulk('APPROVE')}>{t('일괄 승인')}</button>
            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => bulk('REJECT')}>{t('일괄 반려')}</button>
            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => bulk('HOLD')}>{t('일괄 보류')}</button>
          </div>
        )}
        <ul className="ai-dict-proposals">
          {list.isLoading && <li className="gh-muted">{t('불러오는 중…')}</li>}
          {page && page.content.length === 0 && <li className="gh-muted">{t('제안이 없습니다.')}</li>}
          {page?.content.map((p: ProposalSummary) => {
            const reviewable = canReview && (p.status === 'PROPOSED' || p.status === 'REVIEWED' || p.status === 'ON_HOLD');
            return (
              <li key={p.id} className="ai-dict-proposal">
                {canReview && (
                  <input type="checkbox" aria-label={p.targetName} disabled={!reviewable} checked={checked.has(p.id)}
                         onChange={() => setChecked((m) => { const next = new Map(m); if (next.has(p.id)) next.delete(p.id); else next.set(p.id, p.version); return next; })} />
                )}
                <button type="button" className="ai-dict-proposal-body" onClick={() => openDetail(p.id)}>
                  <span className="ai-dict-proposal-head">
                    <span className={`gh-label ${STATUS_CLASS[p.status]}`}>{STATUS_LABEL[p.status]}</span>
                    <span className="ai-dict-name">{p.targetName}</span>
                    <span className="gh-muted ai-dict-small">{CERTAINTY_LABEL[p.certainty]} · {t('근거 {0}/{1}', p.evidenceVerified, p.evidenceTotal)} · {p.model ?? '-'}</span>
                    {p.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
                  </span>
                  <span className="ai-dict-proposal-text">{p.confirmedDescription ?? p.proposedDescription}</span>
                </button>
              </li>
            );
          })}
        </ul>
        {page && page.totalPages > 1 && (
          <div className="ai-dict-buttons">
            <button type="button" className="btn btn-sm" disabled={filter.page === 0} onClick={() => setFilter({ ...filter, page: filter.page - 1 })}>{t('이전')}</button>
            <span className="gh-muted">{filter.page + 1} / {page.totalPages}</span>
            <button type="button" className="btn btn-sm" disabled={filter.page + 1 >= page.totalPages} onClick={() => setFilter({ ...filter, page: filter.page + 1 })}>{t('다음')}</button>
          </div>
        )}
      </section>
    </div>
  );
}

function key(row: { tableId: number; columnId: number | null }) {
  return `${row.tableId}:${row.columnId ?? ''}`;
}

function ReasonForm({ required, onSubmit }: { required: boolean; onSubmit: (reason: string | null) => void }) {
  const [reason, setReason] = useState('');
  return (
    <form className="ai-dict-reason-form" onSubmit={(e) => { e.preventDefault(); if (!required || reason.trim()) onSubmit(reason.trim() || null); }}>
      <textarea className="ai-dict-textarea" rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)}
                placeholder={required ? t('반려 사유 (필수)') : t('보류 사유 (선택)')} aria-label={t('사유')} autoFocus />
      <div className="ai-dict-buttons">
        <button type="submit" className="btn btn-primary" disabled={required && !reason.trim()}>{t('확인')}</button>
      </div>
    </form>
  );
}
