import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import {
  CHANGE_TYPE, COLLECTABLE, COLLECTION_STATUS, COLLECTION_TYPE, EXECUTION_TYPE, inProgress, TARGET_RESULT, TARGET_TYPE,
  type CatalogSchema, type Execution, type ExecutionDetail, type PageResult, type SourceChange, type SourceConnection,
} from './model';
import { CONNECTIONS_KEY, ConnectionPicker, NoConnection, orDash, Pager, useCanManageSource, useSelectedConnection, useSourceConnections } from './shared';
import '../../styles/source.css';

const EXECUTION_PAGE = 20;
const CHANGE_PAGE = 50;
/** 진행 중인 수집의 상태를 다시 읽는 간격 */
const POLL_MS = 2000;

/**
 * 메타데이터 수집 (M-4a) : SourceCollectionRestController 의 수동 최신화(FULL·PARTIAL)·취소·실행 목록·실행 상세·변경 이력.
 * 수집은 서버가 백그라운드에서 돌리고(202 + 실행 ID) 이 화면은 실행 상세를 주기적으로 읽어 상태를 보여 준다.
 * 진행률은 서버가 주지 않으므로 만들지 않는다 (건수는 서버가 채운 값만). COMPLETED 만 원본 카탈로그에 반영된 것이다.
 */
export function SourceCollectionPage() {
  const connections = useSourceConnections();
  const { selected, select, searchParams, setSearchParams } = useSelectedConnection(connections.data);
  const executionParam = Number(searchParams.get('executionId')) || null;
  const selectExecution = (id: number | null) => setSearchParams((prev) => {
    const next = new URLSearchParams(prev);
    if (id) next.set('executionId', String(id));
    else next.delete('executionId');
    return next;
  });

  return (
    <>
      <PageHeader category={t('원본 DB 관리')} title={t('메타데이터 수집')}
        description={t('원본 DB 의 스키마·테이블·컬럼·제약조건·인덱스·FK 를 읽어 원본 카탈로그에 반영합니다. 수집은 서버에서 백그라운드로 실행되며 원본 DB 는 읽기 전용으로 조회합니다. 완료된 수집만 반영되고, 실패하거나 취소한 수집은 반영하지 않습니다.')} />
      {connections.isError ? <p role="alert">{t('연결 목록을 불러오지 못했습니다.')} {connections.error.message}</p>
        : connections.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : !selected ? <NoConnection />
        : (
          <>
            <ConnectionPicker connections={connections.data} selected={selected} onSelect={select} />
            <CollectionPanel key={selected.id} connection={selected} executionId={executionParam} onSelectExecution={selectExecution} />
          </>
        )}
    </>
  );
}

function CollectionPanel({ connection, executionId, onSelectExecution }: {
  connection: SourceConnection;
  executionId: number | null;
  onSelectExecution: (id: number | null) => void;
}) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const canManage = useCanManageSource();
  const [page, setPage] = useState(0);
  const [type, setType] = useState<'FULL' | 'PARTIAL'>('FULL');
  const [picked, setPicked] = useState<string[]>([]);
  const [extra, setExtra] = useState('');

  const executions = useQuery({
    queryKey: ['source-executions', connection.id, page],
    queryFn: () => api<PageResult<Execution>>(`/api/source/connections/${enc(connection.id)}/collections?page=${page}&size=${EXECUTION_PAGE}`, { silent: true }),
    refetchInterval: (query) => (query.state.data?.data.some((e) => inProgress(e.status)) ? POLL_MS : false),
  });
  const running = executions.data?.data.find((e) => inProgress(e.status));
  // 진행 중이던 수집이 끝나면 연결 목록(수집한 스키마 수)과 PARTIAL 후보 스키마를 다시 읽는다
  const lastRunning = useRef<number | null>(null);
  useEffect(() => {
    if (running) {
      lastRunning.current = running.id;
    } else if (lastRunning.current !== null) {
      lastRunning.current = null;
      void queryClient.invalidateQueries({ queryKey: [CONNECTIONS_KEY] });
      void queryClient.invalidateQueries({ queryKey: ['source-schemas', connection.id] });
    }
  }, [running, queryClient, connection.id]);
  const current = executionId ?? running?.id ?? executions.data?.data[0]?.id ?? null;

  // PARTIAL 대상 후보 : 이미 수집한 스키마 (원본 이름 그대로). 처음 수집할 때는 이름을 직접 적는다
  const schemas = useQuery({
    queryKey: ['source-schemas', connection.id],
    queryFn: () => api<PageResult<CatalogSchema>>(`/api/source/connections/${enc(connection.id)}/schemas?page=0&size=200`, { silent: true }),
    enabled: type === 'PARTIAL',
  });

  const typed = extra.split(',').map((s) => s.trim()).filter(Boolean);
  const targets = Array.from(new Set([...picked, ...typed]));
  const collectable = COLLECTABLE.has(connection.dbType);
  const blocked = !collectable ? t('이 DB 유형은 아직 수집할 수 없습니다.')
    : !connection.enabled ? t('사용 중지된 연결입니다.')
    : !connection.driverAvailable ? t('이 DB 유형의 JDBC 드라이버가 없습니다.')
    : running ? t('이 연결의 수집이 진행 중입니다.')
    : null;

  const refresh = useMutation({
    mutationFn: () => api<{ executionId: number }>(`/api/source/connections/${enc(connection.id)}/collections`, {
      method: 'POST',
      body: { type, schemas: type === 'PARTIAL' ? targets : [] },
    }),
    onSuccess: async (accepted) => {
      setPage(0);
      onSelectExecution(accepted.executionId);
      await queryClient.invalidateQueries({ queryKey: ['source-executions', connection.id] });
    },
  });

  const start = () => {
    if (type === 'PARTIAL' && targets.length === 0) {
      dialog.alert(t('수집할 스키마를 하나 이상 고르거나 입력하세요.'));
      return;
    }
    const message = type === 'FULL'
      ? t('{0} 의 시스템 스키마를 뺀 모든 스키마를 수집하시겠습니까? 원본에서 사라진 스키마·테이블은 삭제로 기록됩니다.', connection.name)
      : t('{0} 의 스키마 {1} 을(를) 수집하시겠습니까? 고른 스키마 밖은 비교하지 않습니다.', connection.name, targets.join(', '));
    dialog.confirm(message, () => refresh.mutate());
  };

  return (
    <>
      {canManage ? (
        <section className="source-card" aria-label={t('수집 실행')}>
          <h3>{t('수집 실행')}</h3>
          <div className="source-type-options" role="radiogroup" aria-label={t('수집 범위')}>
            <label><input type="radio" name="collectionType" checked={type === 'FULL'} onChange={() => setType('FULL')} /> {t('전체 (FULL)')} <span className="gh-muted">{t('시스템 스키마를 뺀 모든 스키마')}</span></label>
            <label><input type="radio" name="collectionType" checked={type === 'PARTIAL'} onChange={() => setType('PARTIAL')} /> {t('일부 스키마 (PARTIAL)')} <span className="gh-muted">{t('고른 스키마만, 범위 밖은 비교하지 않음')}</span></label>
          </div>
          {type === 'PARTIAL' && (
            <div className="source-partial">
              {schemas.data && schemas.data.data.length > 0 && (
                <fieldset className="source-schema-checks">
                  <legend className="gh-muted">{t('수집한 스키마')}</legend>
                  {schemas.data.data.filter((s) => s.present).map((s) => (
                    <label key={s.id} className="gh-mono">
                      <input type="checkbox" checked={picked.includes(s.schemaName)}
                        onChange={(e) => setPicked(e.target.checked ? [...picked, s.schemaName] : picked.filter((n) => n !== s.schemaName))} /> {s.schemaName}
                    </label>
                  ))}
                </fieldset>
              )}
              <div className="form-group">
                <label htmlFor="sourceExtraSchemas">{t('스키마 이름 직접 입력 (쉼표로 구분)')}</label>
                <input id="sourceExtraSchemas" type="text" className="form-control gh-mono" placeholder={t('예: public, sales')} value={extra} onChange={(e) => setExtra(e.target.value)} />
                <p className="form-help">{t('원본 이름 그대로 비교합니다 (대소문자 구분).')}</p>
              </div>
            </div>
          )}
          <div className="source-card-actions">
            {blocked && <span className="gh-muted" role="status">{blocked}</span>}
            <button type="button" className="btn btn-primary" disabled={!!blocked || refresh.isPending} onClick={start}>
              {refresh.isPending ? t('요청 중…') : t('수집 시작')}
            </button>
          </div>
        </section>
      ) : (
        <p className="gh-muted source-readonly">{t('수집 실행·취소는 이 프로젝트의 관리자 또는 ADMIN 만 할 수 있습니다. 이력은 조회할 수 있습니다.')}</p>
      )}

      <div className="source-split">
        <section className="source-card" aria-label={t('수집 이력')}>
          <h3>{t('수집 이력')}</h3>
          {executions.isError ? <p role="alert">{t('수집 이력을 불러오지 못했습니다.')} {executions.error.message}</p>
            : !executions.data ? <p role="status">{t('불러오는 중입니다.')}</p>
            : executions.data.data.length === 0 ? <p className="gh-muted">{t('아직 수집한 적이 없습니다.')}</p>
            : (
              <>
                <ul className="source-exec-list">
                  {executions.data.data.map((e) => {
                    const status = COLLECTION_STATUS[e.status];
                    return (
                      <li key={e.id}>
                        <button type="button" className={`source-exec-item${e.id === current ? ' is-active' : ''}`} aria-current={e.id === current ? 'true' : undefined} onClick={() => onSelectExecution(e.id)}>
                          <span className="source-exec-head">
                            <strong>#{e.id}</strong>
                            <Label variant={status.variant} dot>{status.label}</Label>
                            <span className="gh-muted">{COLLECTION_TYPE[e.collectionType] ?? e.collectionType}</span>
                          </span>
                          <span className="gh-muted">{formatDateTime(e.startedAt)}{e.reflectedOrder != null ? ` · ${t('반영 순서 {0}', e.reflectedOrder)}` : ''}</span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <Pager page={page} size={EXECUTION_PAGE} total={executions.data.recordsTotal} onPage={setPage} />
              </>
            )}
        </section>

        <section className="source-card source-grow-card" aria-label={t('수집 상세')}>
          {current ? <ExecutionView key={current} executionId={current} canManage={canManage} connectionId={connection.id} />
            : <p className="gh-muted">{t('수집 이력에서 실행을 고르면 결과가 보입니다.')}</p>}
        </section>
      </div>
    </>
  );
}

function ExecutionView({ executionId, canManage, connectionId }: { executionId: number; canManage: boolean; connectionId: number }) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [changePage, setChangePage] = useState(0);
  const detail = useQuery({
    queryKey: ['source-execution', executionId],
    queryFn: () => api<ExecutionDetail>(`/api/source/collections/${enc(executionId)}`, { silent: true }),
    refetchInterval: (query) => (inProgress(query.state.data?.execution.status) ? POLL_MS : false),
  });
  const execution = detail.data?.execution;
  const finished = execution && !inProgress(execution.status);
  const changes = useQuery({
    queryKey: ['source-execution-changes', executionId, changePage],
    queryFn: () => api<PageResult<SourceChange>>(`/api/source/collections/${enc(executionId)}/changes?page=${changePage}&size=${CHANGE_PAGE}`, { silent: true }),
    enabled: execution?.status === 'COMPLETED',
  });

  const cancel = useMutation({
    mutationFn: () => api<Execution>(`/api/source/collections/${enc(executionId)}/cancel`, { method: 'POST' }),
    onSettled: async () => {
      await detail.refetch();
      await queryClient.invalidateQueries({ queryKey: ['source-executions', connectionId] });
    },
  });

  if (detail.isError) return <p role="alert">{t('수집 결과를 불러오지 못했습니다.')} {detail.error.message}</p>;
  if (!detail.data || !execution) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const { targets } = detail.data;
  const status = COLLECTION_STATUS[execution.status];

  return (
    <div className="source-exec-detail" aria-live="polite">
      <div className="source-exec-title">
        <h3>{t('수집 #{0}', execution.id)}</h3>
        <Label variant={status.variant} dot>{status.label}</Label>
        {inProgress(execution.status) && canManage && (
          <button type="button" className="btn btn-sm btn-danger" disabled={cancel.isPending}
            onClick={() => dialog.confirm(t('진행 중인 수집을 취소하시겠습니까? 취소한 수집은 반영하지 않습니다.'), () => cancel.mutate())}>
            {cancel.isPending ? t('취소 중…') : t('수집 취소')}
          </button>
        )}
      </div>
      {inProgress(execution.status) && <p className="gh-muted" role="status">{t('서버에서 수집하고 있습니다. 이 화면을 떠나도 수집은 계속됩니다.')}</p>}
      {execution.status === 'FAILED' && (
        <p className="source-error" role="alert">{execution.errorMessage ?? t('수집에 실패했습니다.')}{execution.errorCode && <span className="gh-mono gh-muted"> ({execution.errorCode})</span>}</p>
      )}
      {execution.status === 'CANCELLED' && <p className="gh-muted">{t('취소된 수집입니다. 원본 카탈로그에 반영하지 않았습니다.')}</p>}

      <dl className="source-dl source-dl-grid">
        <dt>{t('범위')}</dt><dd>{COLLECTION_TYPE[execution.collectionType] ?? execution.collectionType}</dd>
        <dt>{t('실행 방식')}</dt><dd>{EXECUTION_TYPE[execution.executionType] ?? execution.executionType}</dd>
        <dt>{t('시작')}</dt><dd>{formatDateTime(execution.startedAt) || '-'}</dd>
        <dt>{t('종료')}</dt><dd>{formatDateTime(execution.endedAt) || '-'}</dd>
        <dt>{t('반영 순서')}</dt><dd>{orDash(execution.reflectedOrder)}</dd>
        <dt>{t('반영 시각')}</dt><dd>{formatDateTime(execution.reflectedAt) || '-'}</dd>
        <dt>{t('테이블·VIEW')}</dt><dd>{orDash(execution.tableCount)}</dd>
        <dt>{t('컬럼')}</dt><dd>{orDash(execution.columnCount)}</dd>
        <dt>{t('새 버전')}</dt><dd>{orDash(execution.versionCount)}</dd>
        <dt>{t('변경')}</dt><dd>{orDash(execution.changeCount)}</dd>
      </dl>
      {execution.status === 'COMPLETED' && execution.reflectedOrder != null && (
        <p><Link to={`/sourceCatalog?connectionId=${connectionId}&order=${execution.reflectedOrder}`}>{t('이 수집 시점의 원본 카탈로그 보기')}</Link></p>
      )}

      {targets.length > 0 && (
        <>
          <h4>{t('대상 스키마')}</h4>
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th>{t('스키마')}</th><th>{t('결과')}</th><th className="text-end">{t('테이블·VIEW')}</th><th className="text-end">{t('컬럼')}</th><th>{t('오류')}</th></tr></thead>
              <tbody>
                {targets.map((target) => {
                  const result = TARGET_RESULT[target.result] ?? { label: target.result, variant: '' as const };
                  return (
                    <tr key={target.pathKey}>
                      <td className="gh-mono">{target.schemaName ?? target.pathKey}</td>
                      <td><Label variant={result.variant}>{result.label}</Label></td>
                      <td className="text-end">{orDash(target.tableCount)}</td>
                      <td className="text-end">{orDash(target.columnCount)}</td>
                      <td className="gh-mono gh-muted">{target.errorCode ?? ''}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      {finished && execution.status === 'COMPLETED' && (
        <>
          <h4>{t('변경 내역')}</h4>
          {changes.isError ? <p role="alert">{t('변경 내역을 불러오지 못했습니다.')} {changes.error.message}</p>
            : !changes.data ? <p role="status">{t('불러오는 중입니다.')}</p>
            : changes.data.data.length === 0 ? <p className="gh-muted">{t('직전 반영과 달라진 것이 없습니다.')}</p>
            : (
              <>
                <ChangeTable changes={changes.data.data} />
                <Pager page={changePage} size={CHANGE_PAGE} total={changes.data.recordsTotal} onPage={setChangePage} />
              </>
            )}
        </>
      )}
    </div>
  );
}

/** 객체별 변경 (수집 상세·카탈로그의 테이블 변경 이력이 같이 쓴다). 변경 전후는 서버가 준 정규화 JSON 그대로 펼쳐 본다 */
export function ChangeTable({ changes, showOrder = false }: { changes: SourceChange[]; showOrder?: boolean }) {
  return (
    <div className="source-table-wrap">
      <table className="source-table">
        <thead>
          <tr>
            {showOrder && <th>{t('반영 순서')}</th>}
            <th>{t('구분')}</th>
            <th>{t('대상')}</th>
            <th>{t('이름')}</th>
            <th>{t('바뀐 항목')}</th>
            <th>{t('변경 전후')}</th>
          </tr>
        </thead>
        <tbody>
          {changes.map((c) => {
            const type = CHANGE_TYPE[c.changeType] ?? { label: c.changeType, variant: '' as const };
            return (
              <tr key={c.id}>
                {showOrder && <td>{c.reflectedOrder}</td>}
                <td><Label variant={type.variant}>{type.label}</Label></td>
                <td>{TARGET_TYPE[c.targetType] ?? c.targetType}</td>
                <td className="gh-mono source-break">{c.targetName}</td>
                <td className="gh-mono source-break">{c.changedFields.length ? c.changedFields.join(', ') : <span className="gh-muted">-</span>}</td>
                <td>
                  {(c.before != null || c.after != null) && (
                    <details className="source-json">
                      <summary>{t('보기')}</summary>
                      {c.before != null && <><div className="gh-muted">{t('변경 전')}</div><pre>{JSON.stringify(c.before, null, 2)}</pre></>}
                      {c.after != null && <><div className="gh-muted">{t('변경 후')}</div><pre>{JSON.stringify(c.after, null, 2)}</pre></>}
                    </details>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
