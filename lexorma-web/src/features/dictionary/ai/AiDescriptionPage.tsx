import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useDialog } from '../../../components/dialog/DialogProvider';
import { PageHeader } from '../../../components/layout/PageHeader';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { aiDictionary, BLIND_NOTICE, CERTAINTY_LABEL, JOB_LABEL, STATUS_CLASS, STATUS_LABEL, type JobSummary, type ProposalSummary } from './aiDictionary';
import { AiStatusNotice } from './AiStatusNotice';
import { ProposalDetailView } from './ProposalDetailView';
import { ReviewReasonSummary } from './ReasonView';
import { SelectAll } from './SelectAll';
import { TargetPicker } from './TargetPicker';
import '../../../styles/ai-dictionary.css';

const OPEN_REVIEWABLE = new Set(['PROPOSED', 'REVIEWED', 'ON_HOLD']);
const FILTERS = ['OPEN', 'PROPOSED', 'REVIEWED', 'REVIEW_REQUIRED', 'ON_HOLD', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'CLOSED', ''] as const;

/**
 * 데이터 사전 › AI 설명 제안 (AI-5). 대상 선택 → 초안 생성(단건·대량) → 검토 목록 → 상세에서 승인·수정 후 승인·반려·보류.
 * AI 제안은 승인 전까지 업무 설명을 바꾸지 않는다. 버튼은 권한(생성 USER, 검토 = 승인 권한)에 따라 보이고, 서버가 다시 확인한다.
 * 주소 : ?proposal=ID (그 제안을 연다) · ?tableId=&columnId= (그 대상의 제안으로 이동 — 테이블·컬럼 화면과 ERD 에서 온다) · 예전 ?tab=evaluation (품질 평가) 은 /aiEvaluation 으로 옮긴다 (App 라우트)
 */
export default function AiDescriptionPage() {
  const { canView, approvalAvailable, user } = useAuth();
  const canGenerate = canView('ai-dictionary-generate');
  const canReview = canView('ai-dictionary-review') && approvalAvailable;
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const initialTable = params.get('tableId') ? Number(params.get('tableId')) : null;
  const initialColumn = params.get('columnId') ? Number(params.get('columnId')) : null;

  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null);
  const [filter, setFilter] = useState({ status: initialTable ? '' : 'OPEN', keyword: '', recheck: false, page: 0, tableId: initialTable });
  const [checked, setChecked] = useState<Map<number, ProposalSummary>>(new Map());

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
    void queryClient.invalidateQueries({ queryKey: ['ai-dict-preview'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-dict-objects'] });
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
    opened.current = true;
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const fail = (e: unknown) => setNotice({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });

  const bulk = (action: 'APPROVE' | 'REJECT' | 'HOLD') => {
    const items = [...checked.values()].map((p) => ({ id: p.id, version: p.version }));
    if (items.length === 0) return;
    // 교체 대상 : 고른 제안 중 생성 때 다른 업무 설명이 있던 것 (서버는 항목마다 생성 때 값과 지금 값을 다시 비교한다)
    const replacing = [...checked.values()].filter((p) => p.replacesBizDescription).length;
    const perform = async (reason: string | null) => {
      setBusy(true);
      setNotice(null);
      try {
        const results = await aiDictionary.bulk(action, items, reason);
        const ok = results.filter((r) => r.outcome !== 'FAILED' && r.outcome !== 'REVIEW_REQUIRED').length;
        const review = results.filter((r) => r.outcome === 'REVIEW_REQUIRED').length;
        const failed = results.filter((r) => r.outcome === 'FAILED').length;
        setNotice({ kind: failed + review > 0 ? 'warn' : 'ok', text: action === 'APPROVE'
          ? t('승인 {0}개, 재검토 필요 {1}개 (근거나 업무 설명이 생성 뒤 바뀌어 반영하지 않음), 실패 {2}개 (다른 사용자가 먼저 처리했거나 상태가 바뀐 제안).', ok, review, failed)
          : t('{0}개 처리, 재검토 필요 {1}개, 실패 {2}개 (다른 사용자가 먼저 처리했거나 상태가 바뀐 제안).', ok, review, failed) });
        setChecked(new Map());
        refresh();
      } catch (e) {
        fail(e);
      } finally {
        setBusy(false);
      }
    };
    if (action === 'APPROVE') {
      dialog.confirm(replacing > 0
        ? t('고른 {0}개 제안을 그대로 승인할까요? 그중 {1}개는 기존 업무 설명을 AI 제안 설명으로 교체합니다. 생성 뒤 업무 설명이나 근거가 바뀐 제안은 반영하지 않고 재검토 필요로 돌립니다.', items.length, replacing)
        : t('고른 {0}개 제안을 그대로 승인할까요? 승인하면 업무 설명에 반영됩니다 (근거가 바뀐 제안은 반영하지 않습니다).', items.length), () => void perform(null));
      return;
    }
    dialog.open({
      title: action === 'REJECT' ? t('일괄 반려') : t('일괄 보류'),
      content: ({ close }) => <ReasonForm required={action === 'REJECT'} onSubmit={(reason) => { close(); void perform(reason); }} />,
    });
  };

  const page = list.data;

  return (
    <div className="ai-dict-page">
      <PageHeader category={t('AI 관리')} title={t('AI 설명 제안')}
                  description={t('AI 가 등록된 메타데이터를 근거로 테이블·컬럼의 업무 설명 초안을 만듭니다. 사람이 승인해야 업무 설명에 반영되며, 원본 COMMENT·DDL 설명·표준 용어 연결은 바꾸지 않습니다.')} />

      <AiStatusNotice />
      {notice && <div className={`ai-dict-callout is-${notice.kind}`} role="status">{notice.text}</div>}

      {/* 선택은 프로젝트마다 따로 : 프로젝트가 바뀌면 새로 시작한다 */}
      <TargetPicker key={user?.projectId ?? 0} canGenerate={canGenerate} busy={busy} setBusy={setBusy} onNotice={setNotice} onOpenProposal={openDetail}
                    onStarted={() => { refresh(); void jobs.refetch(); }} jobActive={!!active}
                    focus={initialTable != null ? { tableId: initialTable, columnId: initialColumn } : null} />

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
        <div className="ai-dict-tabs" role="group" aria-label={t('검토 상태')}>
          {([['', t('전체')], ['OPEN', t('검토 대기 전체')], ['ON_HOLD', STATUS_LABEL.ON_HOLD], ['REVIEW_REQUIRED', STATUS_LABEL.REVIEW_REQUIRED]] as const).map(([value, label]) => (
            <button key={value} type="button" className={`btn btn-sm${filter.status === value ? ' btn-primary' : ''}`} aria-pressed={filter.status === value}
                    onClick={() => setFilter({ ...filter, status: value, page: 0 })}>{label}</button>
          ))}
        </div>
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
                             reviewable.forEach((p) => { if (all) next.set(p.id, p); else next.delete(p.id); });
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
            {[...checked.values()].some((p) => p.replacesBizDescription) && (
              <span className="gh-muted">{t('기존 설명 교체 {0}개', [...checked.values()].filter((p) => p.replacesBizDescription).length)}</span>
            )}
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
                         onChange={() => setChecked((m) => { const next = new Map(m); if (next.has(p.id)) next.delete(p.id); else next.set(p.id, p); return next; })} />
                )}
                <button type="button" className="ai-dict-proposal-body" onClick={() => openDetail(p.id)}>
                  <span className="ai-dict-proposal-head">
                    <span className={`gh-label ${STATUS_CLASS[p.status]}`}>{STATUS_LABEL[p.status]}</span>
                    <span className="ai-dict-name">{p.targetName}</span>
                    {p.blind
                      ? <span className="gh-label gh-label-attention" title={BLIND_NOTICE}>{t('평가 블라인드')}</span>
                      : <span className="gh-muted ai-dict-small">{p.certainty ? CERTAINTY_LABEL[p.certainty] : '-'} · {t('근거 {0}/{1}', p.evidenceVerified, p.evidenceTotal)} · {p.model ?? '-'}</span>}
                    {p.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
                    {p.replacesBizDescription && OPEN_REVIEWABLE.has(p.status) && <span className="gh-label" title={t('승인하면 기존 업무 설명을 교체합니다')}>{t('기존 설명 교체')}</span>}
                  </span>
                  {p.blind
                    ? <span className="ai-dict-proposal-text gh-muted">{BLIND_NOTICE}</span>
                    : <span className="ai-dict-proposal-text">{p.confirmedDescription ?? p.proposedDescription}</span>}
                  {p.status === 'REVIEW_REQUIRED' && <span className="ai-dict-small gh-muted">{t('재검토 사유')} : <ReviewReasonSummary raw={p.reviewReason} action="REVIEW_REQUIRED" /> — {t('열어서 근거 재검증·새 초안·종결')}</span>}
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
