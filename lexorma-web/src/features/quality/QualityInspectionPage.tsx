import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useToast } from '../../components/toast/ToastProvider';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t, tc } from '../../lib/i18n';
import { QUALITY_FINDING_GRID_MIN_WIDTH, QUALITY_RUN_GRID_MIN_WIDTH, qualityFindingColumns, qualityRunColumns } from './columns';
import { DomainDuplicatesNotice, FindingDetailView, RunDetailView, SeverityLabel, duration, findingMessage, inspectionKey } from './inspectionDialogs';
import {
  LAST_CHANGES, OUTCOMES, RULE_GROUPS, RULES, SEVERITIES, TARGET_TYPES, endReasonLabel, lastChangeLabel, ruleInfo, ruleLabel, runErrorText, runOutcome, targetTypeLabel,
  type LastChange, type RuleCode, type RuleGroup, type RuleSettings, type Run, type Severity, type Summary, type TargetType,
} from './model';

type Tab = 'results' | 'runs' | 'rules';
type StatusFilter = 'OPEN' | 'RESOLVED' | 'ALL';
type ExclusionFilter = 'NOT_EXCLUDED' | 'EXCLUDED' | 'ALL';
type RuleScope = 'ACTIVE' | 'INACTIVE' | 'ALL';

/** 진행 중인 검사가 있을 때만 상태를 다시 읽는 간격 (원본 수집·AI 설명 생성 화면과 같은 2초) */
const POLL_MS = 2000;

interface Filters {
  group: RuleGroup | '';
  rule: RuleCode | '';
  severity: Severity | '';
  targetType: TargetType | '';
  status: StatusFilter;
  exclusion: ExclusionFilter;
  ruleScope: RuleScope;
  lastChange: LastChange | '';
}

const DEFAULT_FILTERS: Filters = { group: '', rule: '', severity: '', targetType: '', status: 'OPEN', exclusion: 'NOT_EXCLUDED', ruleScope: 'ACTIVE', lastChange: '' };

/** 주소의 조건 (대시보드·요약 카드에서 넘어올 때) : ?rule= · ?severity= · ?exclusion= · ?group= */
function filtersFrom(params: URLSearchParams): Filters {
  const rule = RULES.find((r) => r.rule === params.get('rule'))?.rule ?? '';
  const severity = SEVERITIES.find((s) => s.severity === params.get('severity'))?.severity ?? '';
  const group = RULE_GROUPS.find((g) => g.group === params.get('group'))?.group ?? '';
  const exclusion = params.get('exclusion') === 'EXCLUDED' ? 'EXCLUDED' : 'NOT_EXCLUDED';
  return { ...DEFAULT_FILTERS, rule, severity, group, exclusion };
}

/** 양의 정수 ID 만 (그 밖의 값은 무시) */
function digits(value: string | null): number | null {
  if (!value || !/^\d{1,15}$/.test(value)) return null;
  const id = Number(value);
  return id > 0 ? id : null;
}

/**
 * 품질 진단 (AI-6 3차 — 기존 '표준화 품질 진단' 메뉴·주소를 그대로 쓴다). 조회할 때마다 계산하던 화면을 대신해 <b>저장된 검사 결과</b>를 보여 준다.
 * 위 : 검사 실행·진행 상태·검사 기준 시각·마지막 실행 결과(정상·부분 실패·실패·빈 검사)·현재 결과 요약. 탭 : 진단 결과 · 실행 이력 · 규칙 설정.
 * 진행 중인 검사가 있을 때만 요약을 2초마다 다시 읽고, 끝나면 목록·이력·규칙을 다시 읽는다 (다시 방문해도 서버의 진행 중 실행으로 복구된다).
 * 현재 요약은 조회 시점의 저장 결과이고, 실행 이력의 건수는 그 실행이 결과를 확정한 시점의 값이다 — 둘은 다를 수 있다 (AI6-3-11).
 */
export function QualityInspectionPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = searchParams.get('tab');
  const [tab, setTab] = useState<Tab>(initialTab === 'runs' || initialTab === 'rules' ? initialTab : 'results');
  const { user, canView } = useAuth();
  const projectId = user?.projectId ?? 0;
  const queryClient = useQueryClient();
  const toast = useToast();
  const findingGrid = useRef<GridHandle>(null);
  const runGrid = useRef<GridHandle>(null);
  const [filters, setFilters] = useState<Filters>(() => filtersFrom(searchParams));

  const summary = useQuery({
    queryKey: inspectionKey(projectId, 'summary'),
    queryFn: () => api<Summary>('/api/qualityInspection/summary', { silent: true }),
    refetchInterval: (query) => (query.state.data?.activeRun ? POLL_MS : false),
  });
  const rules = useQuery({
    queryKey: inspectionKey(projectId, 'rules'),
    queryFn: () => api<RuleSettings>('/api/qualityInspection/rules'),
  });

  // 진행 중이던 실행이 끝나면(같은 화면에서 보고 있던 경우) 저장 결과를 쓰는 곳을 모두 다시 읽는다. 다음 검사를 요청하지 않는다
  const activeId = summary.data?.activeRun?.id ?? null;
  const previousActive = useRef<number | null>(null);
  useEffect(() => {
    if (previousActive.current != null && activeId == null) {
      findingGrid.current?.reload();
      runGrid.current?.reload();
      void queryClient.invalidateQueries({ queryKey: inspectionKey(projectId) });
    }
    previousActive.current = activeId;
  }, [activeId, projectId, queryClient]);

  const request = useMutation({
    mutationFn: () => api<Run>('/api/qualityInspection/runs', { method: 'POST', body: {} }),
    onSuccess: (run) => {
      // 서버가 등록한 실행으로 바로 진행 상태를 보인다 (화면이 상태를 만들지 않는다)
      queryClient.setQueryData<Summary>(inspectionKey(projectId, 'summary'), (old) => (old ? { ...old, activeRun: run, latestRun: run } : old));
      previousActive.current = run.id;
      runGrid.current?.reload();
      void summary.refetch();
    },
    onError: () => void summary.refetch(),
  });

  const data = summary.data;
  const active = data?.activeRun ?? null;
  const canRun = canView('quality-inspection-run');
  const recheck = (rules.data?.rules ?? []).filter((r) => r.recheckNeeded).length;

  const dialogApi = useDialog();
  const openRun = (runId: number) => {
    dialogApi.open({
      title: t('실행 상세'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <RunDetailView projectId={projectId} runId={runId} close={close} />,
    });
  };

  const changedAfterExclusion = () => {
    findingGrid.current?.reload();
    void queryClient.invalidateQueries({ queryKey: inspectionKey(projectId, 'summary') });
  };
  const openFinding = (findingId: number) => {
    dialogApi.open({
      title: t('진단 결과 상세'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <FindingDetailView projectId={projectId} findingId={findingId} close={close} onChanged={changedAfterExclusion} onRun={openRun} />,
    });
  };

  // 주소로 온 결과·실행 상세 (AI 도우미의 품질 근거 — AI-7 3차) : ?finding=<결과 ID> · ?tab=runs&run=<실행 ID>.
  // 목록 필터와 상관없이 상세를 연다 (해결·예외·꺼진 규칙의 결과도 기본 필터에 가려지지 않는다). 연 뒤에는 주소에서 지워 새로고침으로 다시 열지 않는다.
  // 상세는 서버가 로그인한 프로젝트 안에서만 찾는다 (다른 프로젝트·지워진 ID 는 상세가 '불러오지 못했습니다' — 프로젝트를 바꾸지 않는다)
  const linkedFinding = digits(searchParams.get('finding'));
  const linkedRun = digits(searchParams.get('run'));
  const handledLink = useRef<string | null>(null);
  useEffect(() => {
    if (linkedFinding == null && linkedRun == null) {
      handledLink.current = null;
      return;
    }
    // 개발 모드의 효과 두 번 실행(StrictMode)에도 한 번만 연다
    const key = `${linkedFinding}:${linkedRun}`;
    if (handledLink.current === key) return;
    handledLink.current = key;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('finding');
      next.delete('run');
      return next;
    }, { replace: true });
    if (linkedFinding != null) {
      setTab('results');
      openFinding(linkedFinding);
    } else if (linkedRun != null) {
      setTab('runs');
      openRun(linkedRun);
    }
  }, [linkedFinding, linkedRun]); // eslint-disable-line react-hooks/exhaustive-deps

  const pickSummary = (patch: Partial<Filters>) => {
    setTab('results');
    setFilters({ ...DEFAULT_FILTERS, ...patch });
  };

  const tabs: [Tab, string][] = [['results', t('진단 결과')], ['runs', t('실행 이력')], ['rules', t('규칙 설정')]];

  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('품질 진단')}
        description={t('표준 사전·물리 모델·DBMS 식별자를 규칙으로 검사한 저장 결과를 봅니다. 검사는 직접 실행하며, 결과는 검사 기준 시각의 데이터로 판정한 값입니다. 테이블·컬럼·표준 항목은 바꾸지 않습니다.')} />

      <section className="quality-status" aria-label={t('품질 진단 상태')}>
        <div className="quality-status-main">
          <StatusLines summary={data} loading={summary.isPending} error={summary.isError} recheck={recheck} onOpenRun={openRun} />
          {canRun && (
            <div className="quality-status-actions">
              <button type="button" className="btn btn-primary" disabled={request.isPending || !!active || summary.isPending}
                onClick={() => request.mutate()}>
                {active ? OUTCOMES[active.status === 'PENDING' ? 'PENDING' : 'RUNNING'].label : request.isPending ? t('요청 중…') : t('품질 진단 실행')}
              </button>
            </div>
          )}
        </div>
        <SummaryCards summary={data} onPick={pickSummary} />
      </section>

      <ul className="nav nav-tabs" role="tablist">
        {tabs.map(([id, label]) => (
          <li className="nav-item" key={id}>
            <a href={`#quality-${id}`} role="tab" aria-selected={tab === id} className={`nav-link${tab === id ? ' active' : ''}`}
              onClick={(e) => { e.preventDefault(); setTab(id); }}>{label}</a>
          </li>
        ))}
      </ul>
      <div className="quality-tab">
        {tab === 'results' && (
          <FindingsTab key={projectId} grid={findingGrid} summary={data} filters={filters} setFilters={setFilters} onOpen={openFinding} />
        )}
        {tab === 'runs' && (
          <>
            <p className="form-help">{t('실행별 건수는 그 실행이 결과를 확정한 시점의 값입니다. 그 뒤의 예외 처리·해제나 규칙 설정 변경으로 바뀌지 않아 위의 현재 결과 요약과 다를 수 있습니다. 과거 실행 당시의 전체 결과 목록은 저장하지 않습니다.')}</p>
            <Grid key={projectId} ref={runGrid} id="qualityRunGrid" minWidth={QUALITY_RUN_GRID_MIN_WIDTH} columns={qualityRunColumns}
              dataUrl="/api/qualityInspection/runs" defaultSort={{ column: 'requestedAt', desc: true }}
              emptyText={t('아직 품질 진단을 실행하지 않았습니다.')}
              onRowClick={(row) => openRun(row.id)}
              renderCell={(field, row) => runCell(field, row as Run)} />
          </>
        )}
        {tab === 'rules' && (
          <RulesTab projectId={projectId} settings={rules.data} loading={rules.isPending} error={rules.isError} active={!!active}
            onSaved={(next) => {
              queryClient.setQueryData(inspectionKey(projectId, 'rules'), next);
              void queryClient.invalidateQueries({ queryKey: inspectionKey(projectId, 'summary') });
              findingGrid.current?.reload();
              toast.success(t('규칙 설정을 저장했습니다. 다음 품질 진단부터 적용됩니다.'));
            }}
            onConflict={() => void rules.refetch()} />
        )}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 상태 · 요약

function StatusLines({ summary, loading, error, recheck, onOpenRun }: {
  summary?: Summary; loading: boolean; error: boolean; recheck: number; onOpenRun: (runId: number) => void;
}) {
  if (loading) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (!summary) return <p className="gh-muted" role="alert">{t('품질 진단 상태를 불러오지 못했습니다.')}</p>;
  const { lastRun, activeRun, latestRun } = summary;
  const latest = latestRun && latestRun.id !== activeRun?.id ? latestRun : null;
  const latestOutcome = latest ? runOutcome(latest) : null;
  const lastOutcome = lastRun ? runOutcome(lastRun) : null;
  const failedLatest = latest && latestOutcome !== 'COMPLETED' && latestOutcome !== 'PARTIAL' && latestOutcome !== 'EMPTY';
  return (
    <div className="quality-status-lines" aria-live="polite">
      {error && <p className="gh-muted" role="status">{t('상태를 다시 불러오지 못했습니다. 잠시 뒤 다시 읽습니다.')}</p>}
      {activeRun && (
        <p className="quality-status-line">
          <Label variant={OUTCOMES[runOutcome(activeRun)].variant} dot>{OUTCOMES[runOutcome(activeRun)].label}</Label>{' '}
          {activeRun.status === 'PENDING' ? t('검사가 대기열에서 시작을 기다리고 있습니다.') : t('검사를 진행하고 있습니다. 끝나면 결과를 다시 불러옵니다.')}
          {' '}<Muted>{t('요청 {0}', formatDateTime(activeRun.requestedAt))}</Muted>
        </p>
      )}
      {!latestRun && !activeRun && <p className="quality-status-line">{t('아직 품질 진단을 실행하지 않았습니다. 검사를 실행해야 결과가 생깁니다.')}</p>}
      {lastRun && (
        <p className="quality-status-line">
          <strong>{t('검사 기준 시각')}</strong> {formatDateTime(lastRun.basisAt)}
          {' · '}{t('마지막 확정 실행')} <button type="button" className="quality-link" onClick={() => onOpenRun(lastRun.id)}>#{lastRun.id}</button>
          {' '}<Label variant={OUTCOMES[lastOutcome!].variant}>{OUTCOMES[lastOutcome!].label}</Label>
          <Muted> {t('저장된 결과는 이 시각의 데이터로 판정한 값입니다. 그 뒤의 변경은 다음 검사에 반영됩니다.')}</Muted>
        </p>
      )}
      {lastRun && lastOutcome === 'PARTIAL' && (
        <div className="quality-notice is-attention" role="status">
          <strong>{t('일부 규칙을 판정하지 못했습니다 ({0}개).', lastRun.failedRules.length)}</strong>{' '}
          {t('판정한 규칙의 결과는 정상 반영되었고, 판정하지 못한 규칙의 결과는 이전 검사 결과가 그대로 남아 있습니다.')}
          <span className="quality-chips">{lastRun.failedRules.map((rule) => <Label key={rule} variant="attention">{ruleLabel(rule)}</Label>)}</span>
        </div>
      )}
      {lastRun && lastOutcome === 'EMPTY' && (
        <div className="quality-notice" role="status">{t('마지막 검사에 적용된 검사 규칙이 없습니다 (모든 규칙이 꺼져 있음). 문제가 없다는 뜻이 아닙니다.')}</div>
      )}
      {latest && failedLatest && (
        <div className="quality-notice is-danger" role="status">
          <Label variant={OUTCOMES[latestOutcome!].variant}>{OUTCOMES[latestOutcome!].label}</Label>{' '}
          {runErrorText(latest)}{' '}
          {lastRun ? t('결과는 마지막 확정 검사(기준 시각 {0}) 그대로입니다.', formatDateTime(lastRun.basisAt)) : t('아직 저장된 검사 결과가 없습니다.')}
          {lastRun && lastOutcome === 'PARTIAL' ? ` ${t('그 검사에서 판정하지 못한 규칙의 결과는 그보다 앞선 검사 기준입니다.')}` : ''}
          {' '}<button type="button" className="quality-link" onClick={() => onOpenRun(latest.id)}>{t('실행 상세')}</button>
        </div>
      )}
      {recheck > 0 && lastRun && (
        <p className="quality-status-line"><Label variant="attention">{t('재검사 필요')}</Label> {t('켜져 있지만 마지막 검사에서 판정하지 않은 규칙이 {0}개 있습니다. 그 규칙의 결과는 그 전 검사 기준입니다.', recheck)}</p>
      )}
    </div>
  );
}

function SummaryCards({ summary, onPick }: { summary?: Summary; onPick: (patch: Partial<Filters>) => void }) {
  const has = !!summary?.lastRun;
  const value = (v: number | undefined) => (has && v != null ? v.toLocaleString() : '-');
  return (
    <>
    <div className="dash dash-stats quality-stats" aria-label={t('현재 결과 요약')}>
      {SEVERITIES.map((s) => (
        <button key={s.severity} type="button" className="dash-stat" disabled={!has} onClick={() => onPick({ severity: s.severity })}>
          <div className="dash-stat-label"><SeverityLabel severity={s.severity} /></div>
          <div className={`dash-stat-value${has && summary!.bySeverity[s.severity] && s.severity !== 'INFO' ? ' is-pending' : ''}`}>{value(summary?.bySeverity[s.severity])}</div>
        </button>
      ))}
      <button type="button" className="dash-stat" disabled={!has} onClick={() => onPick({ exclusion: 'EXCLUDED' })}>
        <div className="dash-stat-label">{t('예외')}</div>
        <div className="dash-stat-value">{value(summary?.openExcluded)}</div>
        <div className="quality-stat-foot">{t('미해결 중 예외 처리')}</div>
      </button>
      <div className="dash-stat is-static">
        <div className="dash-stat-label">{t('해결')}</div>
        <div className="dash-stat-value">{value(summary?.ruleSatisfied)}</div>
        <div className="quality-stat-foot">{has ? t('대상 삭제 {0}건 따로', summary!.targetDeleted) : ''}</div>
      </div>
    </div>
    <p className="quality-stat-note gh-muted">{t('지금 쓰는 규칙의 미해결 결과를 조회 시점 기준으로 셉니다 (예외·꺼진 규칙 결과는 오류·주의·참고에서 뺍니다). 점수나 등급은 매기지 않습니다.')}</p>
    </>
  );
}

// ---------------------------------------------------------------- 진단 결과 탭

function FindingsTab({ grid, summary, filters, setFilters, onOpen }: {
  grid: RefObject<GridHandle | null>; summary?: Summary; filters: Filters; setFilters: (f: Filters) => void; onOpen: (id: number) => void;
}) {
  const set = (patch: Partial<Filters>) => {
    const next = { ...filters, ...patch };
    // 함께 쓸 수 없는 조합을 고친다 : 규칙은 대상 구분 안에서, 마지막 변화는 상태에 맞게
    if (next.rule && next.group && ruleInfo(next.rule)?.group !== next.group) next.rule = '';
    if (next.status === 'OPEN' && next.lastChange === 'RESOLVED') next.lastChange = '';
    if (next.status === 'RESOLVED' && next.lastChange && next.lastChange !== 'RESOLVED') next.lastChange = '';
    setFilters(next);
  };
  const params = useMemo(() => {
    const p: Record<string, string> = { status: filters.status, exclusion: filters.exclusion, ruleScope: filters.ruleScope };
    if (filters.group) p.group = filters.group;
    if (filters.rule) p.rule = filters.rule;
    if (filters.severity) p.severity = filters.severity;
    if (filters.targetType) p.targetType = filters.targetType;
    if (filters.lastChange) p.lastChange = filters.lastChange;
    return p;
  }, [filters]);
  const changes = LAST_CHANGES.filter((c) => (filters.status === 'OPEN' ? c.change !== 'RESOLVED' : filters.status === 'RESOLVED' ? c.change === 'RESOLVED' : true));
  const isDefault = JSON.stringify(filters) === JSON.stringify(DEFAULT_FILTERS);

  const emptyText = (() => {
    if (!summary) return t('진단 결과가 없습니다.');
    if (!summary.latestRun) return t('아직 품질 진단을 실행하지 않았습니다. 검사 이력이 없으므로 문제가 없다는 뜻이 아닙니다.');
    if (!summary.lastRun) return summary.activeRun ? t('첫 검사를 진행하고 있습니다.') : t('검사 결과를 아직 만들지 못했습니다. 실행 이력에서 실패 사유를 확인하세요.');
    if (!isDefault) return t('조회 조건에 해당하는 결과가 없습니다.');
    const outcome = runOutcome(summary.lastRun);
    if (outcome === 'EMPTY') return t('적용된 검사 규칙이 없어 결과가 없습니다.');
    if (outcome === 'PARTIAL') return t('현재 조회 조건에 해당하는 문제가 없습니다. 다만 일부 규칙은 마지막 검사에서 판정하지 못했습니다.');
    return t('현재 조회 조건에 해당하는 문제가 없습니다.');
  })();

  return (
    <>
      <Grid ref={grid} id="qualityFindingGrid" minWidth={QUALITY_FINDING_GRID_MIN_WIDTH} columns={qualityFindingColumns}
        dataUrl="/api/qualityInspection/findings" params={params} defaultSort={{ column: 'severity' }}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('대상 구분')} value={filters.group} onChange={(e) => set({ group: e.target.value as RuleGroup | '' })}>
              <option value="">{t('전체 대상 구분')}</option>
              {RULE_GROUPS.map((g) => <option key={g.group} value={g.group}>{g.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('진단 규칙')} value={filters.rule} onChange={(e) => set({ rule: e.target.value as RuleCode | '' })}>
              <option value="">{t('전체 규칙')}</option>
              {RULES.filter((r) => !filters.group || r.group === filters.group).map((r) => <option key={r.rule} value={r.rule}>{r.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('심각도')} value={filters.severity} onChange={(e) => set({ severity: e.target.value as Severity | '' })}>
              <option value="">{t('전체 심각도')}</option>
              {SEVERITIES.map((s) => <option key={s.severity} value={s.severity}>{s.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('대상 유형')} value={filters.targetType} onChange={(e) => set({ targetType: e.target.value as TargetType | '' })}>
              <option value="">{t('전체 대상 유형')}</option>
              {TARGET_TYPES.map((x) => <option key={x.targetType} value={x.targetType}>{x.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('실제 문제 상태')} value={filters.status} onChange={(e) => set({ status: e.target.value as StatusFilter })}>
              <option value="OPEN">{t('미해결')}</option>
              <option value="RESOLVED">{t('해결')}</option>
              <option value="ALL">{t('전체 상태')}</option>
            </select>
            <select className="search-column-select" aria-label={t('예외 여부')} value={filters.exclusion} onChange={(e) => set({ exclusion: e.target.value as ExclusionFilter })}>
              <option value="NOT_EXCLUDED">{t('예외 아님')}</option>
              <option value="EXCLUDED">{t('예외')}</option>
              <option value="ALL">{t('예외 전체')}</option>
            </select>
            <select className="search-column-select" aria-label={t('규칙 활성 상태')} value={filters.ruleScope} onChange={(e) => set({ ruleScope: e.target.value as RuleScope })}>
              <option value="ACTIVE">{t('켜진 규칙')}</option>
              <option value="INACTIVE">{tc('규칙 활성 상태', '꺼진 규칙')}</option>
              <option value="ALL">{t('전체 규칙 상태')}</option>
            </select>
            <select className="search-column-select" aria-label={t('마지막 변화')} value={filters.lastChange} onChange={(e) => set({ lastChange: e.target.value as LastChange | '' })}>
              <option value="">{t('전체 마지막 변화')}</option>
              {changes.map((c) => <option key={c.change} value={c.change}>{c.label}</option>)}
            </select>
            {!isDefault && <button type="button" className="btn btn-sm" onClick={() => setFilters(DEFAULT_FILTERS)}>{t('조건 초기화')}</button>}
          </>
        }
        emptyText={emptyText}
        onRowClick={(row) => onOpen(row.id)}
        renderCell={(field, row) => findingCell(field, row)} />
      <p className="form-help">{t('마지막 변화는 그 결과가 마지막으로 판정된 검사에서의 변화입니다 (특정 과거 실행의 변화가 아닙니다). 예외는 해결이 아니라 기본 목록·요약에서 빼 두는 표시입니다.')}</p>
      <DomainDuplicatesNotice />
    </>
  );
}

function findingCell(field: string, row: Row) {
  switch (field) {
    case 'severity': return <SeverityLabel severity={row.severity} />;
    case 'rule': return (
      <span>
        {ruleLabel(row.rule)}
        {!row.ruleActive && <> <Label>{t('꺼진 규칙')}</Label></>}
      </span>
    );
    case 'targetType': return targetTypeLabel(row.targetType);
    case 'targetName': return <Mono>{row.targetName}</Mono>;
    case 'message': return <span className="quality-message">{findingMessage(row.rule, row.actualValue, row.expectedValue)}</span>;
    case 'status': return row.status === 'OPEN'
      ? <Label variant="attention">{t('미해결')}</Label>
      : <span><Label variant="success">{t('해결')}</Label> <Muted>{endReasonLabel(row.endReason)}</Muted></span>;
    case 'excluded': return row.excluded ? <Label variant="done">{t('예외')}</Label> : <Muted>-</Muted>;
    case 'lastChange': return lastChangeLabel(row.lastChange);
    default: return undefined;
  }
}

function runCell(field: string, run: Run) {
  const failed = run.status === 'FAILED' || run.status === 'INTERRUPTED';
  const count = (value: number) => (failed || run.status === 'PENDING' || run.status === 'RUNNING' ? <Muted>-</Muted> : value);
  switch (field) {
    case 'status': {
      const outcome = OUTCOMES[runOutcome(run)];
      return <Label variant={outcome.variant} dot>{outcome.label}</Label>;
    }
    case 'requestedBy': return run.requestedBy ?? <Muted>-</Muted>;
    case 'basisAt': return run.basisAt ? formatDateTime(run.basisAt) : <Muted>-</Muted>;
    case 'endedAt': return run.endedAt ? formatDateTime(run.endedAt) : <Muted>-</Muted>;
    case 'duration': return duration(run.startedAt ?? run.requestedAt, run.endedAt) ?? <Muted>-</Muted>;
    case 'appliedRuleCount': return run.status === 'PENDING' ? <Muted>-</Muted> : run.appliedRuleCount;
    case 'failedRules': return run.failedRules.length ? <Label variant="attention">{run.failedRules.length}</Label> : <Muted>0</Muted>;
    case 'NEW': case 'PERSISTING': case 'REOPENED': case 'EXCLUDED': return count(run.totals[field]);
    case 'resolved': return count(run.totals.RULE_SATISFIED + run.totals.TARGET_DELETED);
    default: return undefined;
  }
}

// ---------------------------------------------------------------- 규칙 설정 탭

function RulesTab({ projectId, settings, loading, error, active, onSaved, onConflict }: {
  projectId: number; settings?: RuleSettings; loading: boolean; error: boolean; active: boolean;
  onSaved: (next: RuleSettings) => void; onConflict: () => void;
}) {
  const dialog = useDialog();
  const [pending, setPending] = useState<RuleCode | null>(null);
  const save = useMutation({
    mutationFn: ({ rule, active: next, expected }: { rule: RuleCode; active: boolean; expected: boolean }) =>
      api<RuleSettings>('/api/qualityInspection/rules', { method: 'PUT', body: { rules: [{ rule, active: next, expectedActive: expected }] }, conflict: 'handled' }),
    onMutate: ({ rule }) => setPending(rule),
    onSettled: () => setPending(null),
    onSuccess: onSaved,
    onError: (error) => {
      // 다른 사용자가 먼저 바꿨다 : 자동으로 다시 보내지 않는다 — 서버 기준으로 다시 읽고 알린다
      onConflict();
      if (error instanceof ApiError && error.code === 'INSPECTION_RULE_SETTING_CONFLICT') {
        dialog.alert(t('다른 사용자가 그사이 이 규칙의 사용 여부를 바꿨습니다. 최신 설정을 다시 불러왔으니 확인한 뒤 다시 바꿔주세요.'));
      }
    },
  });
  void projectId;

  if (loading) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (error || !settings) return <p className="gh-muted" role="alert">{t('규칙 설정을 불러오지 못했습니다.')}</p>;
  return (
    <>
      <p className="form-help">
        {settings.editable
          ? t('규칙마다 사용 여부만 바꿀 수 있습니다 (심각도·판정 조건은 바꿀 수 없습니다). 바꿔도 검사를 실행하지 않으며, 끈 규칙의 결과는 지우거나 해결하지 않고 기본 목록·요약에서만 뺍니다.')
          : t('규칙 설정은 프로젝트 관리자와 ADMIN 만 바꿀 수 있습니다.')}
        {active && <> {t('진행 중인 검사는 시작할 때의 규칙으로 끝납니다.')}</>}
      </p>
      {RULE_GROUPS.map((g) => (
        <section key={g.group} aria-labelledby={`quality-rules-${g.group}`}>
          <h2 id={`quality-rules-${g.group}`} className="quality-subtitle">{g.label}</h2>
          <div className="table-responsive">
            <table className="table quality-rule-table">
              <thead>
                <tr>
                  <th scope="col">{t('사용')}</th>
                  <th scope="col">{t('진단 규칙')}</th>
                  <th scope="col">{t('심각도')}</th>
                  <th scope="col">{t('규칙 설명')}</th>
                  <th scope="col">{t('마지막 검사')}</th>
                </tr>
              </thead>
              <tbody>
                {settings.rules.filter((r) => r.group === g.group).map((r) => {
                  const info = ruleInfo(r.rule);
                  return (
                    <tr key={r.rule}>
                      <td>
                        <input type="checkbox" checked={r.active} disabled={!settings.editable || pending != null}
                          aria-label={t('{0} 사용', info?.label ?? r.rule)}
                          onChange={(e) => save.mutate({ rule: r.rule, active: e.target.checked, expected: r.active })} />
                      </td>
                      <td>{info?.label ?? t('알 수 없는 규칙')}{!r.active && <> <Label>{t('꺼짐')}</Label></>}</td>
                      <td><SeverityLabel severity={r.severity} /></td>
                      <td className="quality-rule-description">{info?.description}</td>
                      <td>
                        {r.lastInspectedAt ? formatDateTime(r.lastInspectedAt) : <Muted>{t('검사 이력 없음')}</Muted>}
                        {r.recheckNeeded && <> <Label variant="attention">{t('재검사 필요')}</Label></>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </>
  );
}
