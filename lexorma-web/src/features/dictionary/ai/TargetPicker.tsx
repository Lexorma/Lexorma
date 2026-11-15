import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronRight, RefreshCw, Sparkles } from 'lucide-react';
import { useAiStatus } from '../../../hooks/useAiStatus';
import { ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { aiDictionary, STATUS_CLASS, STATUS_LABEL, type JobBody, type PreviewOutcome, type TargetRow } from './aiDictionary';
import { SelectAll } from './SelectAll';

const OUTCOME_LABEL: Record<PreviewOutcome, string> = {
  CREATE: t('생성'),
  OPEN_PROPOSAL: t('건너뜀 (검토 대기 제안 있음)'),
  DESCRIBED: t('제외 (업무 설명 있음)'),
};

type Notice = { kind: 'ok' | 'warn' | 'error'; text: string };

/**
 * AI 설명 제안 › 대상 선택 (AI-5 후속). 테이블 목록(개수만) → 펼치면 그 테이블·컬럼의 설명 상세(한 번 조회). 선택(체크)과 펼치기(화살표)는 따로다.
 * 고른 테이블은 "테이블 업무 설명 생성"·"설명 없는 컬럼 함께 생성" 옵션대로, 고른 컬럼은 그 컬럼만 만든다. 기본은 업무 설명이 없는 대상만 (있는 것은 "다시 만들기" 를 켜야 한다).
 * 실제 생성 대상은 서버의 미리 보기(실행과 같은 규칙)로 보여 준다. 만든 제안은 대상마다 따로 검토한다.
 * 테이블 목록은 서버가 쪽 단위(30개)로 검색·거르기·정렬한다 (AI-5-PAGE). 선택은 쪽과 따로 두므로 쪽을 넘기거나 검색어를 바꿔도 남고, 미리 보기·생성은 선택 전체로 한다.
 * 머리줄 체크는 지금 쪽의 테이블만 고르거나 푼다 — 검색 결과 전체를 한 번에 고르는 기능은 없다 (결정 2026-11-15).
 */
export function TargetPicker({ canGenerate, busy, setBusy, onNotice, onOpenProposal, onStarted, jobActive, focus }: {
  canGenerate: boolean; busy: boolean; setBusy: (b: boolean) => void; onNotice: (n: Notice | null) => void; onOpenProposal: (id: number) => void;
  onStarted: () => void; jobActive: boolean; focus: { tableId: number; columnId: number | null } | null;
}) {
  const ai = useAiStatus();
  const [keyword, setKeyword] = useState('');
  // 입력을 잠시 멈춘 뒤 서버에 묻는다 (치는 동안 요청을 줄인다). 응답은 조건별 캐시라 늦게 온 예전 응답이 지금 결과를 덮지 않는다
  const [query, setQuery] = useState('');
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(keyword.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [keyword]);
  const [onlyMissing, setOnlyMissing] = useState(false);
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<Set<number>>(new Set(focus ? [focus.tableId] : []));
  const [tables, setTables] = useState<Set<number>>(new Set(focus && focus.columnId == null ? [focus.tableId] : []));
  const [columns, setColumns] = useState<Map<number, number>>(new Map(focus?.columnId != null ? [[focus.columnId, focus.tableId]] : []));
  const [withTable, setWithTable] = useState(true);
  const [withColumns, setWithColumns] = useState(true);
  const [redo, setRedo] = useState(false);

  const list = useQuery({
    queryKey: ['ai-dict-targets', 'tables', onlyMissing, query, page],
    queryFn: () => aiDictionary.targetTables({ onlyMissing, keyword: query, page }),
    placeholderData: keepPreviousData,
  });
  const shown = list.data?.content ?? [];
  const total = list.data?.totalElements ?? 0;
  const pages = Math.max(1, list.data?.totalPages ?? 1);
  useEffect(() => setPage(0), [query, onlyMissing]);
  // 목록이 줄어 지금 쪽이 범위를 벗어나면 마지막 쪽으로 (빈 쪽에 머물지 않게)
  useEffect(() => {
    if (list.data && !list.isPlaceholderData && page > 0 && page >= list.data.totalPages) setPage(Math.max(0, list.data.totalPages - 1));
  }, [list.data, list.isPlaceholderData, page]);

  const body: JobBody = useMemo(() => ({
    scope: 'SELECTED',
    targets: [...[...tables].map((id) => ({ tableId: id, columnId: null })), ...[...columns.entries()].map(([columnId, tableId]) => ({ tableId, columnId }))],
    tables: withTable, columns: withColumns, onlyMissing: !redo,
  }), [tables, columns, withTable, withColumns, redo]);
  const any = tables.size + columns.size > 0;
  const preview = useQuery({ queryKey: ['ai-dict-preview', body], queryFn: () => aiDictionary.preview(body), enabled: any && canGenerate });

  const toggleTable = (id: number) => setTables((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleColumn = (r: TargetRow) => setColumns((m) => { const n = new Map(m); if (n.has(r.columnId!)) n.delete(r.columnId!); else n.set(r.columnId!, r.tableId); return n; });
  const toggleExpand = (id: number) => setExpanded((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  const generate = async () => {
    const p = preview.data;
    if (!p || p.targets === 0) return;
    setBusy(true);
    onNotice(null);
    try {
      const runnable = p.items.filter((i) => i.outcome !== 'DESCRIBED');
      if (runnable.length === 1) {
        const one = runnable[0];
        const result = await aiDictionary.generate(one.tableId, one.columnId, false);
        onNotice(result.outcome === 'SKIPPED'
          ? { kind: 'warn', text: t('검토 대기 중인 제안이 있어 새로 만들지 않았습니다. 다시 만들려면 상세에서 다시 만드세요.') }
          : { kind: 'ok', text: t('제안을 만들었습니다. 승인 전까지 업무 설명은 바뀌지 않습니다.') });
        onOpenProposal(result.proposal.id);
      } else {
        const job = await aiDictionary.startJob(body);
        onNotice({ kind: 'ok', text: t('{0}개 대상의 제안을 차례로 만듭니다. 진행 상태는 아래에서 볼 수 있습니다.', job.targetCount) });
      }
      setTables(new Set());
      setColumns(new Map());
      onStarted();
    } catch (e) {
      onNotice({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  const missingTables = async () => {
    setBusy(true);
    onNotice(null);
    try {
      const job = await aiDictionary.startJob({ scope: 'MISSING_TABLES' });
      onNotice({ kind: 'ok', text: t('{0}개 대상의 제안을 차례로 만듭니다. 진행 상태는 아래에서 볼 수 있습니다.', job.targetCount) });
      onStarted();
    } catch (e) {
      onNotice({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  const recheck = async () => {
    setBusy(true);
    onNotice(null);
    try {
      const r = await aiDictionary.recheck();
      onNotice({ kind: r.reviewRequired + r.recheckFlagged > 0 ? 'warn' : 'ok', text: t('{0}개를 확인했습니다. 재검토 필요 {1}개, 승인 뒤 근거 변경 {2}개.', r.checked, r.reviewRequired, r.recheckFlagged) });
      onStarted();
    } catch (e) {
      onNotice({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  const p = preview.data;
  const tablePicks = shown.filter((r) => tables.has(r.tableId)).length;
  // 지금 쪽에 보이지 않는 선택 (다른 쪽·검색 결과 밖)
  const hiddenPicks = [...tables].filter((id) => !shown.some((r) => r.tableId === id)).length;
  return (
    <section className="ai-dict-card">
      <h3>{t('대상 선택')}</h3>
      <div className="ai-dict-toolbar">
        <input className="search-input" placeholder={t('테이블 이름·코멘트')} aria-label={t('테이블 이름·코멘트')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
        <label className="ai-dict-check"><input type="checkbox" checked={onlyMissing} onChange={(e) => setOnlyMissing(e.target.checked)} /> {t('업무 설명이 빠진 테이블만')}</label>
        <span className="gh-muted ai-dict-small" role="status">{list.data ? t('테이블 {0}개', total.toLocaleString()) : ''}</span>
      </div>
      {canGenerate && any && (
        <p className="ai-dict-small" role="status">
          {t('선택 : 테이블 {0}개 · 컬럼 {1}개', tables.size, columns.size)}
          {hiddenPicks > 0 && <span className="gh-muted"> · {t('지금 쪽에 보이지 않는 테이블 {0}개 포함 (다른 쪽·검색 결과 밖)', hiddenPicks)}</span>}
        </p>
      )}
      <div className="ai-dict-table-wrap ai-dict-tree">
        <table className="ai-dict-table">
          <thead>
            <tr>
              {canGenerate && <th className="ai-dict-col-check">
                <SelectAll label={tablePicks >= shown.length && shown.length > 0 ? t('현재 페이지 테이블 선택 해제') : t('현재 페이지 테이블 전체 선택')} total={shown.length} selected={tablePicks}
                           onChange={(all) => setTables((s) => { const n = new Set(s); shown.forEach((r) => { if (all) n.add(r.tableId); else n.delete(r.tableId); }); return n; })} />
              </th>}
              <th className="ai-dict-col-check"><span className="sr-only">{t('펼치기')}</span></th>
              <th>{t('테이블')}</th><th>{t('컬럼 · 업무 설명 없음')}</th><th>{t('업무 설명')}</th><th>{t('최근 제안')}</th>
            </tr>
          </thead>
          <tbody>
            {list.isLoading && <tr><td colSpan={6} className="gh-muted">{t('불러오는 중…')}</td></tr>}
            {list.isError && <tr><td colSpan={6} className="gh-muted">{t('대상을 불러오지 못했습니다.')}</td></tr>}
            {list.data && !list.isPlaceholderData && shown.length === 0 && <tr><td colSpan={6} className="gh-muted">{t('대상이 없습니다.')}</td></tr>}
            {shown.map((r) => {
              const open = expanded.has(r.tableId);
              return (
                <Fragment key={r.tableId}>
                  <tr className={open ? 'is-open' : undefined}>
                    {canGenerate && <td className="ai-dict-col-check"><input type="checkbox" aria-label={t('{0} 선택', r.name)} checked={tables.has(r.tableId)} onChange={() => toggleTable(r.tableId)} /></td>}
                    <td className="ai-dict-col-check">
                      <button type="button" className="ai-dict-expand" aria-expanded={open} aria-label={open ? t('{0} 접기', r.name) : t('{0} 펼치기', r.name)} onClick={() => toggleExpand(r.tableId)}>
                        {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                      </button>
                    </td>
                    <td><span className="ai-dict-name">{r.name}</span>{r.description && <span className="gh-muted"> · {r.description}</span>}</td>
                    <td>{r.detail?.columnCount ?? 0} · <span className={r.detail?.missingColumnCount ? undefined : 'gh-muted'}>{r.detail?.missingColumnCount ?? 0}</span></td>
                    <td>{r.hasBizDescription ? t('있음') : <span className="gh-muted">{t('없음')}</span>}</td>
                    <td><ProposalLink row={r} onOpen={onOpenProposal} /></td>
                  </tr>
                  {open && (
                    <tr className="ai-dict-tree-detail">
                      <td colSpan={6}>
                        <TableDetail tableId={r.tableId} canGenerate={canGenerate} columns={columns} toggleColumn={toggleColumn} setColumns={setColumns}
                                     onOpenProposal={onOpenProposal} focusColumn={focus?.tableId === r.tableId ? focus.columnId : undefined} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="ai-dict-buttons" aria-busy={list.isFetching}>
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(0)}>{t('처음')}</button>
          {/* 연달아 눌러도 누른 만큼 옮기도록 함수형 갱신 (마지막 쪽을 넘지 않게) */}
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>{t('이전')}</button>
          <span className="gh-muted">{page + 1} / {pages}</span>
          <button type="button" className="btn btn-sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}>{t('다음')}</button>
          <button type="button" className="btn btn-sm" disabled={page + 1 >= pages} onClick={() => setPage(pages - 1)}>{t('마지막')}</button>
        </div>
      )}
      {canGenerate && (
        <>
          <fieldset className="ai-dict-checks ai-dict-options">
            <legend className="ai-dict-small">{t('고른 테이블')}</legend>
            <label className="ai-dict-check"><input type="checkbox" checked={withTable} onChange={(e) => setWithTable(e.target.checked)} /> {t('테이블 업무 설명 생성')}</label>
            <label className="ai-dict-check"><input type="checkbox" checked={withColumns} onChange={(e) => setWithColumns(e.target.checked)} /> {t('설명이 없는 컬럼 함께 생성')}</label>
            <label className="ai-dict-check"><input type="checkbox" checked={redo} onChange={(e) => setRedo(e.target.checked)} /> {t('업무 설명이 있는 대상도 다시 만들기')}</label>
          </fieldset>
          {any && (
            <div className="ai-dict-preview" role="status">
              {preview.isLoading && <span className="gh-muted">{t('생성 대상을 확인하는 중…')}</span>}
              {preview.isError && <span className="gh-muted">{preview.error instanceof ApiError ? preview.error.message : t('생성 대상을 확인하지 못했습니다.')}</span>}
              {p && (
                <>
                  <strong>{t('실제 생성 대상 {0}개', p.create)}</strong>
                  <span className="gh-muted"> · {t('건너뜀 {0} (검토 대기 제안 있음) · 제외 {1} (업무 설명 있음)', p.openProposal, p.described)}</span>
                  {p.targets > p.limit && <div className="ai-dict-callout is-warn">{t('한 번에 {0}개까지 만들 수 있습니다. 범위를 좁혀주세요.', p.limit)}</div>}
                  <details>
                    <summary>{t('대상 목록 보기')}</summary>
                    <ul className="ai-dict-list ai-dict-preview-list">
                      {p.items.map((i) => (
                        <li key={`${i.tableId}:${i.columnId ?? ''}`}>
                          <span className="gh-label">{i.targetType === 'TABLE' ? t('테이블') : t('컬럼')}</span> <span className="ai-dict-name">{i.name}</span>
                          <span className={i.outcome === 'CREATE' ? undefined : 'gh-muted'}> · {OUTCOME_LABEL[i.outcome]}</span>
                        </li>
                      ))}
                    </ul>
                  </details>
                </>
              )}
            </div>
          )}
          <div className="ai-dict-buttons">
            <button type="button" className="btn btn-primary" disabled={busy || !ai.ready || !p || p.targets === 0 || p.targets > p.limit || (p.targets > 1 && jobActive)} onClick={generate}
                    title={ai.ready ? undefined : t('AI Provider 를 지금 쓸 수 없습니다')}>
              <Sparkles size={14} aria-hidden="true" /> {busy ? t('만드는 중…') : t('초안 만들기 ({0}개)', p?.create ?? 0)}
            </button>
            {any && <button type="button" className="btn" disabled={busy} onClick={() => { setTables(new Set()); setColumns(new Map()); }}>{t('선택 해제')}</button>}
            <button type="button" className="btn" disabled={busy || jobActive || !ai.ready} onClick={missingTables}>{t('업무 설명 없는 테이블 모두')}</button>
            <button type="button" className="btn" disabled={busy} onClick={recheck}><RefreshCw size={14} aria-hidden="true" /> {t('근거 변경 확인')}</button>
          </div>
        </>
      )}
    </section>
  );
}

/** 펼친 테이블 : 테이블 상세(설명들·개수)와 컬럼 상세 (한 번 조회, 캐시) */
function TableDetail({ tableId, canGenerate, columns, toggleColumn, setColumns, onOpenProposal, focusColumn }: {
  tableId: number; canGenerate: boolean; columns: Map<number, number>; toggleColumn: (r: TargetRow) => void;
  setColumns: (f: (m: Map<number, number>) => Map<number, number>) => void; onOpenProposal: (id: number) => void;
  /** 테이블·컬럼 화면에서 온 대상 : 이 테이블이면 컬럼 ID(테이블 자신이면 null), 아니면 undefined */
  focusColumn: number | null | undefined;
}) {
  const q = useQuery({ queryKey: ['ai-dict-targets', tableId, false, ''], queryFn: () => aiDictionary.targets(tableId, false), staleTime: 30_000 });
  const opened = useRef(false);
  useEffect(() => {
    // 테이블·컬럼 화면에서 온 대상이면 그 대상의 최근 제안을 연다 (한 번)
    if (opened.current || !q.data || focusColumn === undefined) return;
    opened.current = true;
    const row = q.data.find((r) => (r.columnId ?? null) === focusColumn);
    if (row?.proposalId) onOpenProposal(row.proposalId);
  }, [q.data]); // eslint-disable-line react-hooks/exhaustive-deps
  if (q.isLoading) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (!q.data) return <p className="gh-muted">{t('불러오지 못했습니다.')}</p>;
  const table = q.data.find((r) => r.targetType === 'TABLE');
  const cols = q.data.filter((r) => r.targetType === 'COLUMN');
  const missing = cols.filter((c) => !c.hasBizDescription);
  const picked = cols.filter((c) => columns.has(c.columnId!)).length;
  const d = table?.detail;
  return (
    <div className="ai-dict-tree-body">
      {d && (
        <dl className="ai-dict-compare">
          <dt>{t('스키마')}</dt><dd>{d.schemaName ?? '-'}</dd>
          <dt>{t('테이블 코멘트')}</dt><dd>{d.logicalName ?? <Missing />}</dd>
          <dt>{t('물리 모델 설명')}</dt><dd>{d.modelDescription ?? <Missing />}</dd>
          <dt>{t('원본 COMMENT')}</dt><dd>{d.sourceComment ?? <Missing />}</dd>
          <dt>{t('업무 설명')}</dt><dd>{d.bizDescription ?? <Missing />}</dd>
          <dt>{t('최근 제안')}</dt><dd><ProposalLink row={table!} onOpen={onOpenProposal} /></dd>
          <dt>{t('컬럼')}</dt><dd>{t('{0}개 · 업무 설명 없음 {1}개', d.columnCount ?? 0, d.missingColumnCount ?? 0)}</dd>
        </dl>
      )}
      {canGenerate && cols.length > 0 && (
        <div className="ai-dict-buttons">
          <label className="ai-dict-check">
            <SelectAll label={t('컬럼 전체 선택')} total={cols.length} selected={picked}
                       onChange={(all) => setColumns((m) => { const n = new Map(m); cols.forEach((c) => { if (all) n.set(c.columnId!, tableId); else n.delete(c.columnId!); }); return n; })} />
            {t('컬럼 전체 선택')}
          </label>
          <button type="button" className="btn btn-sm" disabled={missing.length === 0}
                  onClick={() => setColumns((m) => { const n = new Map(m); missing.forEach((c) => n.set(c.columnId!, tableId)); return n; })}>
            {t('설명 없는 컬럼 선택 ({0})', missing.length)}
          </button>
        </div>
      )}
      <ul className="ai-dict-column-cards">
        {cols.map((c) => {
          const cd = c.detail;
          return (
            <li key={c.columnId} className={focusColumn === c.columnId ? 'is-focus' : undefined}>
              <div className="ai-dict-proposal-head">
                {canGenerate && <input type="checkbox" aria-label={t('{0} 선택', c.name)} checked={columns.has(c.columnId!)} onChange={() => toggleColumn(c)} />}
                <span className="ai-dict-name">{cd?.physicalName ?? c.name}</span>
                {cd?.logicalName && <span className="gh-muted">{cd.logicalName}</span>}
                {cd?.dataType && <span className="gh-label">{cd.dataType}</span>}
                {cd?.pk && <span className="gh-label gh-label-accent">PK</span>}
                {cd?.fk && <span className="gh-label">FK</span>}
                <ProposalLink row={c} onOpen={onOpenProposal} />
              </div>
              <dl className="ai-dict-compare ai-dict-small">
                {(cd?.termName || cd?.domainName) && <><dt>{t('표준 용어 · 도메인')}</dt><dd>{cd?.termName ?? '-'} · {cd?.domainName ?? '-'}</dd></>}
                <dt>{t('물리 모델 설명')}</dt><dd>{cd?.modelDescription ?? <Missing />}</dd>
                <dt>{t('원본 COMMENT')}</dt><dd>{cd?.sourceComment ?? <Missing />}</dd>
                <dt>{t('업무 설명')}</dt><dd>{cd?.bizDescription ?? <Missing />}</dd>
              </dl>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function Missing() {
  return <span className="gh-muted">{t('설명 없음')}</span>;
}

function ProposalLink({ row, onOpen }: { row: TargetRow; onOpen: (id: number) => void }) {
  return (
    <>
      {row.proposalStatus ? (
        <button type="button" className="ai-dict-link" onClick={() => onOpen(row.proposalId!)}>
          <span className={`gh-label ${STATUS_CLASS[row.proposalStatus]}`}>{STATUS_LABEL[row.proposalStatus]}</span>
        </button>
      ) : <span className="gh-muted">{t('제안 없음')}</span>}
      {row.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
    </>
  );
}
