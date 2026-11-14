import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, ApiError, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { useTableDetail } from '../table/tableDetail';
import { dbTypeLabel } from './model';
import { Pager, useCanManageSource } from './shared';
import '../../styles/source.css';

/**
 * 원본 매핑 관리 (M-4c) : 가져오기 기준 연결, 원본 스키마 ↔ 모델 스키마, 원본 TABLE ↔ 모델 TABLE (기준 연결 안에서 1:1).
 * 후보(같은 이름·PostgreSQL public → DEFAULT)는 제안만 하고, 확정은 사용자가 한다. 매핑만 저장하며 원본 DB·물리 모델은 바꾸지 않는다.
 * 상태(재확인·충돌·원본 없음·모델 없음)는 서버가 마지막 반영 시점으로 계산한다. 변경 버튼은 프로젝트 관리자·ADMIN 에게만 보인다 (서버도 같은 기준).
 */
type Status = 'UNMAPPED' | 'MAPPED' | 'RECHECK' | 'CONFLICT' | 'SOURCE_MISSING' | 'MODEL_MISSING';
type Reason = 'SCHEMA_UNMAPPED' | 'SCHEMA_MISMATCH' | 'NOT_TABLE' | 'RECREATED' | 'REMOVED' | 'MODEL_DELETED' | 'NO_COLLECTION';

interface Candidate { id: number; name: string; taken: boolean }
interface Connection { id: number; name: string; dbType: string; enabled: boolean; collectable: boolean }
interface SchemaRow {
  sourceSchemaId: number; sourceSchemaName: string; tableCount: number; viewCount: number; mappingId: number | null; schemaId: number | null; schemaName: string | null;
  defaultSchema: boolean; confirmedOrder: number | null; status: Status; reason: Reason | null; candidate: Candidate | null;
}
interface TableRow {
  sourceTableId: number; sourceSchemaId: number; sourceSchemaName: string | null; sourceTableName: string; sourceComment: string | null; mappingId: number | null;
  tableId: number | null; tableName: string | null; tableKorName: string | null; schemaId: number | null; schemaName: string | null; confirmedOrder: number | null;
  status: Status; reason: Reason | null; candidate: Candidate | null;
}
interface Overview {
  base: Connection | null; connections: Connection[]; reflectedOrder: number | null; executionId: number | null; reflectedAt: string | null;
  schemaCounts: Partial<Record<Status, number>>; tableCounts: Partial<Record<Status, number>>; viewCount: number;
  preserved: { connectionId: number; connectionName: string | null; schemaMappings: number; tableMappings: number }[];
  schemas: SchemaRow[]; modelSchemas: { id: number; schemaName: string; isDefault: boolean; mappedSourceSchemaId: number | null }[];
}
interface TablePage { rows: TableRow[]; total: number; counts: Partial<Record<Status, number>> }
interface ModelTable { id: number; tableName: string; tableKorName: string | null; schemaId: number; schemaName: string; mappedSourceTableId: number | null }

const STATUS: Record<Status, { label: string; variant: LabelVariant }> = {
  UNMAPPED: { label: t('미매핑'), variant: '' },
  MAPPED: { label: t('매핑됨'), variant: 'success' },
  RECHECK: { label: t('재확인 필요'), variant: 'attention' },
  CONFLICT: { label: t('충돌'), variant: 'danger' },
  SOURCE_MISSING: { label: t('원본 없음'), variant: 'danger' },
  MODEL_MISSING: { label: t('모델 없음'), variant: 'danger' },
};
const STATUSES = Object.keys(STATUS) as Status[];

const REASON: Record<Reason, string> = {
  SCHEMA_UNMAPPED: t('스키마 매핑이 없거나 유효하지 않습니다'),
  SCHEMA_MISMATCH: t('모델 테이블의 스키마가 스키마 매핑과 다릅니다'),
  NOT_TABLE: t('원본이 TABLE 이 아닙니다'),
  RECREATED: t('원본이 사라졌다가 같은 이름으로 다시 생겼습니다'),
  REMOVED: t('마지막 수집에서 원본이 사라졌습니다'),
  MODEL_DELETED: t('매핑된 모델 객체가 삭제되었습니다'),
  NO_COLLECTION: t('반영된 수집이 없습니다'),
};

const TABLE_PAGE = 50;
const OVERVIEW_KEY = 'source-mapping';

function StatusLabel({ status, reason }: { status: Status; reason: Reason | null }) {
  const s = STATUS[status];
  return (
    <span className="source-mapping-status">
      <Label variant={s.variant} dot>{s.label}</Label>
      {reason && <span className="gh-muted source-sub">{REASON[reason]}</span>}
    </span>
  );
}

function Counts({ counts }: { counts: Partial<Record<Status, number>> }) {
  const shown = STATUSES.filter((s) => counts[s]);
  if (!shown.length) return <span className="gh-muted">-</span>;
  return <span className="source-labels">{shown.map((s) => <Label key={s} variant={STATUS[s].variant}>{STATUS[s].label} {counts[s]}</Label>)}</span>;
}

export function SourceMappingPage() {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const canManage = useCanManageSource();
  const overview = useQuery({ queryKey: [OVERVIEW_KEY], queryFn: () => api<Overview>('/api/sourceMapping', { silent: true }) });
  const [baseChoice, setBaseChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const data = overview.data;

  useEffect(() => {
    setBaseChoice(data?.base ? String(data.base.id) : '');
  }, [data?.base?.id]);

  const refresh = () => queryClient.invalidateQueries({ queryKey: [OVERVIEW_KEY] });

  /** 변경 요청 : 409(확인 필요)는 서버 문구로 확인을 받고 acknowledge 로 다시 보낸다 */
  const saveBase = async (acknowledge = false) => {
    setBusy(true);
    try {
      await api('/api/sourceMapping/base', { method: 'PUT', body: { connectionId: baseChoice ? Number(baseChoice) : null, acknowledge }, silent: true });
      await refresh();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'SOURCE_MAPPING_CONFLICT' && !acknowledge) {
        // 서버 문구는 "공통 문구 [자세한 내용]" 이다 : 확인 대화상자에는 자세한 내용(남는 매핑 수)만 보인다
        dialog.confirm(/\[([\s\S]*)\]\s*$/.exec(e.message)?.[1] ?? e.message, () => void saveBase(true));
      } else {
        dialog.alert((e as Error).message);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader category={t('원본 DB 관리')} title={t('원본 매핑 관리')}
        description={t('수집한 원본 스키마·TABLE 을 Lexorma 물리 모델의 스키마·TABLE 에 1:1 로 연결합니다. 같은 이름은 후보로 제안만 하고, 확정해야 저장됩니다. 매핑만 저장하며 원본 DB 와 물리 모델은 바꾸지 않습니다.')} />
      {overview.isError ? <p role="alert">{t('매핑 정보를 불러오지 못했습니다.')} {overview.error.message}</p>
        : !data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : (
          <>
            <section className="source-card" aria-label={t('가져오기 기준 연결')}>
              <h3>{t('가져오기 기준 연결')}</h3>
              <div className="source-mapping-base">
                <div className="form-group source-picker">
                  <label htmlFor="sourceMappingBase">{t('기준 연결')}</label>
                  <select id="sourceMappingBase" className="form-control" value={baseChoice} disabled={!canManage || busy} onChange={(e) => setBaseChoice(e.target.value)}>
                    <option value="">{t('지정 안 함')}</option>
                    {data.connections.map((c) => <option key={c.id} value={c.id}>{c.name} · {dbTypeLabel(c.dbType)}{!c.collectable ? ` (${t('수집 미지원')})` : ''}</option>)}
                  </select>
                </div>
                {canManage && (
                  <button type="button" className="btn btn-primary" disabled={busy || baseChoice === (data.base ? String(data.base.id) : '')} onClick={() => void saveBase()}>
                    {data.base && !baseChoice ? t('기준 연결 해제') : t('기준 연결 저장')}
                  </button>
                )}
              </div>
              {!data.connections.length && <p className="gh-muted">{t('등록된 원본 DB 연결이 없습니다.')} <Link to="/sourceConnection">{t('연결 관리')}</Link></p>}
              {data.base && (
                <dl className="source-dl source-dl-grid">
                  <dt>{t('마지막 반영')}</dt>
                  <dd>{data.reflectedOrder != null ? t('반영 순서 {0} · 수집 #{1} · {2}', data.reflectedOrder, data.executionId, formatDateTime(data.reflectedAt)) : <span className="source-warn">{t('완료된 수집이 없어 매핑을 확정할 수 없습니다.')}</span>}</dd>
                  <dt>{t('스키마 매핑')}</dt><dd><Counts counts={data.schemaCounts} /></dd>
                  <dt>{t('TABLE 매핑')}</dt><dd><Counts counts={data.tableCounts} /></dd>
                  <dt>VIEW</dt><dd className="gh-muted">{t('{0}개 · VIEW 는 정의 SQL 을 수집하지 않아 매핑하지 않습니다', data.viewCount)}</dd>
                </dl>
              )}
              {data.base && data.reflectedOrder == null && <p><Link to={`/sourceCollection?connectionId=${data.base.id}`}>{t('메타데이터 수집으로 이동')}</Link></p>}
              {data.preserved.length > 0 && (
                <p className="gh-muted source-sub">
                  {t('다른 연결에 남아 있는 매핑 (그 연결을 다시 기준으로 고르면 쓰입니다) : ')}
                  {data.preserved.map((p) => t('{0} — 스키마 {1}건 · TABLE {2}건', p.connectionName ?? `#${p.connectionId}`, p.schemaMappings, p.tableMappings)).join(', ')}
                </p>
              )}
              {!canManage && <p className="gh-muted source-readonly">{t('기준 연결과 매핑은 이 프로젝트의 관리자 또는 ADMIN 만 바꿀 수 있습니다. 조회만 할 수 있습니다.')}</p>}
            </section>

            {data.base && data.reflectedOrder != null && (
              <>
                <SchemaMappings data={data} canManage={canManage} onChanged={refresh} />
                <TableMappings data={data} canManage={canManage} onChanged={refresh} />
              </>
            )}
          </>
        )}
    </>
  );
}

function SchemaMappings({ data, canManage, onChanged }: { data: Overview; canManage: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const [choice, setChoice] = useState<Record<number, string>>({});

  const confirm = async (row: SchemaRow, schemaId: number) => {
    try {
      await api('/api/sourceMapping/schemas', { method: 'PUT', body: { sourceSchemaId: row.sourceSchemaId, schemaId } });
      onChanged();
    } catch { /* 공통 알림 */ }
  };
  const release = (row: SchemaRow) => {
    dialog.confirm(t('{0} 스키마 매핑을 해제하시겠습니까? 이 원본 스키마의 TABLE 매핑도 함께 해제됩니다. 물리 모델은 바뀌지 않습니다.', row.sourceSchemaName), async () => {
      await api(`/api/sourceMapping/schemas/${enc(row.sourceSchemaId)}`, { method: 'DELETE' });
      onChanged();
    });
  };

  return (
    <section className="source-card" aria-label={t('스키마 매핑')}>
      <h3>{t('스키마 매핑')}</h3>
      <div className="source-table-wrap">
        <table className="source-table">
          <thead><tr><th>{t('원본 스키마')}</th><th className="text-end">{t('TABLE · VIEW')}</th><th>{t('모델 스키마')}</th><th>{t('상태')}</th><th>{t('작업')}</th></tr></thead>
          <tbody>
            {data.schemas.map((row) => {
              const selected = choice[row.sourceSchemaId] ?? (row.schemaId != null ? String(row.schemaId) : row.candidate && !row.candidate.taken ? String(row.candidate.id) : '');
              return (
                <tr key={row.sourceSchemaId}>
                  <td className="gh-mono source-break">{row.sourceSchemaName}</td>
                  <td className="text-end">{row.tableCount} · {row.viewCount}</td>
                  <td>
                    {row.schemaName ? <span className="gh-mono">{row.schemaName}</span> : <span className="gh-muted">-</span>}
                    {row.defaultSchema && <> <Label>{t('기본 스키마')}</Label></>}
                    {row.candidate && (
                      <div className="source-sub">
                        <Label variant={row.candidate.taken ? 'attention' : 'accent'}>{t('후보')}</Label> <span className="gh-mono">{row.candidate.name}</span>
                        {row.candidate.taken && <span className="gh-muted"> · {t('이미 다른 원본에 매핑됨')}</span>}
                      </div>
                    )}
                    {row.confirmedOrder != null && <div className="gh-muted source-sub">{t('확정 : 반영 순서 {0}', row.confirmedOrder)}</div>}
                  </td>
                  <td><StatusLabel status={row.status} reason={row.reason} /></td>
                  <td>
                    {canManage && (
                      <div className="source-actions">
                        {row.status !== 'SOURCE_MISSING' && (
                          <>
                            <select className="form-control source-inline-select" aria-label={t('{0} 에 매핑할 모델 스키마', row.sourceSchemaName)} value={selected}
                              onChange={(e) => setChoice({ ...choice, [row.sourceSchemaId]: e.target.value })}>
                              <option value="">{t('모델 스키마 선택')}</option>
                              {data.modelSchemas.map((s) => (
                                <option key={s.id} value={s.id} disabled={s.mappedSourceSchemaId != null && s.mappedSourceSchemaId !== row.sourceSchemaId}>
                                  {s.schemaName}{s.isDefault ? ` (${t('기본')})` : ''}{s.mappedSourceSchemaId != null && s.mappedSourceSchemaId !== row.sourceSchemaId ? ` — ${t('매핑됨')}` : ''}
                                </option>
                              ))}
                            </select>
                            <button type="button" className="btn btn-sm btn-primary" disabled={!selected || (row.status === 'MAPPED' && selected === String(row.schemaId))}
                              onClick={() => void confirm(row, Number(selected))}>{row.mappingId != null ? t('다시 확정') : t('확정')}</button>
                          </>
                        )}
                        {row.mappingId != null && <button type="button" className="btn btn-sm btn-danger" onClick={() => release(row)}>{t('해제')}</button>}
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="form-help">{t('원본 스키마는 모델 스키마 하나에만, 모델 스키마도 원본 스키마 하나에만 연결합니다. 원본에만 있는 스키마는 미매핑으로 두며, 모델 스키마를 자동으로 만들지 않습니다.')}</p>
    </section>
  );
}

function TableMappings({ data, canManage, onChanged }: { data: Overview; canManage: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const { openTableDetailById } = useTableDetail();
  const [schema, setSchema] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(keyword.trim()); setPage(0); }, 300);
    return () => clearTimeout(timer);
  }, [keyword]);
  const params = new URLSearchParams({ page: String(page), size: String(TABLE_PAGE) });
  if (schema) params.set('sourceSchemaId', schema);
  if (status) params.set('status', status);
  if (query) params.set('keyword', query);
  const tables = useQuery({
    queryKey: [OVERVIEW_KEY, 'tables', schema, status, query, page, data.reflectedOrder],
    queryFn: () => api<TablePage>(`/api/sourceMapping/tables?${params}`, { silent: true }),
  });
  const reload = () => { void tables.refetch(); onChanged(); };

  const confirm = async (row: TableRow, tableId: number) => {
    try {
      await api('/api/sourceMapping/tables', { method: 'PUT', body: { sourceTableId: row.sourceTableId, tableId } });
      reload();
    } catch { /* 공통 알림 */ }
  };
  const release = (row: TableRow) => {
    dialog.confirm(t('{0}.{1} 의 TABLE 매핑을 해제하시겠습니까? 물리 모델은 바뀌지 않습니다.', row.sourceSchemaName ?? '', row.sourceTableName), async () => {
      await api(`/api/sourceMapping/tables/${enc(row.mappingId)}`, { method: 'DELETE' });
      reload();
    });
  };
  const pick = (row: TableRow) => {
    const schemaRow = data.schemas.find((s) => s.sourceSchemaId === row.sourceSchemaId);
    if (schemaRow?.schemaId == null || schemaRow.status !== 'MAPPED') {
      dialog.alert(t('원본 스키마 {0} 의 스키마 매핑을 먼저 확정해주세요.', row.sourceSchemaName ?? ''));
      return;
    }
    dialog.open({
      title: t('모델 TABLE 선택 · {0}.{1}', row.sourceSchemaName ?? '', row.sourceTableName),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ModelTablePicker schemaId={schemaRow.schemaId!} schemaName={schemaRow.schemaName ?? ''} sourceTableId={row.sourceTableId}
        onPick={(id) => { close(); void confirm(row, id); }} onCancel={close} />,
    });
  };

  return (
    <section className="source-card" aria-label={t('TABLE 매핑')}>
      <h3>{t('TABLE 매핑')}</h3>
      <div className="source-mapping-filter">
        <select className="form-control" aria-label={t('원본 스키마')} value={schema} onChange={(e) => { setSchema(e.target.value); setPage(0); }}>
          <option value="">{t('전체 원본 스키마')}</option>
          {data.schemas.filter((s) => s.tableCount > 0 || s.mappingId != null).map((s) => <option key={s.sourceSchemaId} value={s.sourceSchemaId}>{s.sourceSchemaName}</option>)}
        </select>
        <select className="form-control" aria-label={t('상태')} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">{t('전체 상태')}</option>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS[s].label}{tables.data?.counts[s] ? ` (${tables.data.counts[s]})` : ''}</option>)}
        </select>
        <input type="search" className="form-control" aria-label={t('TABLE 이름 검색')} placeholder={t('원본·모델 TABLE 이름 검색')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      </div>
      {tables.isError ? <p role="alert">{t('TABLE 매핑을 불러오지 못했습니다.')} {tables.error.message}</p>
        : !tables.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : tables.data.rows.length === 0 ? <p className="gh-muted">{t('조건에 맞는 원본 TABLE 이 없습니다.')}</p>
        : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('원본 TABLE')}</th><th>{t('모델 TABLE')}</th><th>{t('상태')}</th><th>{t('작업')}</th></tr></thead>
                <tbody>
                  {tables.data.rows.map((row) => (
                    <tr key={`${row.sourceTableId}-${row.mappingId ?? 'n'}`}>
                      <td className="source-break">
                        {row.status === 'SOURCE_MISSING'
                          ? <span className="gh-mono">{row.sourceSchemaName}.{row.sourceTableName}</span>
                          : <Link className="gh-mono" to={`/sourceCatalog?connectionId=${data.base!.id}&schemaId=${row.sourceSchemaId}&tableId=${row.sourceTableId}`}>{row.sourceSchemaName}.{row.sourceTableName}</Link>}
                        {row.sourceComment && <div className="gh-muted source-sub">{row.sourceComment}</div>}
                      </td>
                      <td className="source-break">
                        {row.tableId != null ? (
                          <button type="button" className="source-link-button gh-mono" onClick={() => void openTableDetailById(row.tableId!)}>{row.schemaName}.{row.tableName}</button>
                        ) : <span className="gh-muted">-</span>}
                        {row.tableKorName && row.tableKorName !== row.tableName && <div className="gh-muted source-sub">{row.tableKorName}</div>}
                        {row.candidate && (
                          <div className="source-sub">
                            <Label variant={row.candidate.taken ? 'attention' : 'accent'}>{t('후보')}</Label> <span className="gh-mono">{row.candidate.name}</span>
                            {row.candidate.taken && <span className="gh-muted"> · {t('이미 다른 원본에 매핑됨')}</span>}
                          </div>
                        )}
                        {row.confirmedOrder != null && <div className="gh-muted source-sub">{t('확정 : 반영 순서 {0}', row.confirmedOrder)}</div>}
                      </td>
                      <td><StatusLabel status={row.status} reason={row.reason} /></td>
                      <td>
                        {canManage && (
                          <div className="source-actions">
                            {row.candidate && !row.candidate.taken && row.mappingId == null && (
                              <button type="button" className="btn btn-sm btn-primary" onClick={() => void confirm(row, row.candidate!.id)}>{t('후보로 확정')}</button>
                            )}
                            {row.status === 'RECHECK' && row.tableId != null && (
                              <button type="button" className="btn btn-sm btn-primary" onClick={() => void confirm(row, row.tableId!)}>{t('다시 확정')}</button>
                            )}
                            {row.status !== 'SOURCE_MISSING' && <button type="button" className="btn btn-sm" onClick={() => pick(row)}>{row.mappingId != null ? t('변경') : t('선택')}</button>}
                            {row.mappingId != null && <button type="button" className="btn btn-sm btn-danger" onClick={() => release(row)}>{t('해제')}</button>}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} size={TABLE_PAGE} total={tables.data.total} onPage={setPage} />
          </>
        )}
      <p className="form-help">{t('원본 TABLE 은 매핑된 모델 스키마의 TABLE 하나에만 연결합니다. 이름이 같아도 자동으로 연결하지 않습니다. 원본이 같은 이름으로 다시 생기면 재확인이 필요합니다.')}</p>
    </section>
  );
}

function ModelTablePicker({ schemaId, schemaName, sourceTableId, onPick, onCancel }: {
  schemaId: number; schemaName: string; sourceTableId: number; onPick: (tableId: number) => void; onCancel: () => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setQuery(keyword.trim()), 300);
    return () => clearTimeout(timer);
  }, [keyword]);
  const tables = useQuery({
    queryKey: [OVERVIEW_KEY, 'modelTables', schemaId, query],
    queryFn: () => api<ModelTable[]>(`/api/sourceMapping/modelTables?schemaId=${enc(schemaId)}${query ? `&keyword=${enc(query)}` : ''}`, { silent: true }),
  });
  return (
    <div className="source-picker-dialog">
      <p className="gh-muted">{t('모델 스키마 {0} 의 TABLE 중에서 고릅니다 (200개까지, 이름으로 좁히세요).', schemaName)}</p>
      <input type="search" className="form-control" aria-label={t('모델 TABLE 검색')} placeholder={t('TABLE 명·논리명')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      {tables.isError ? <p role="alert">{tables.error.message}</p>
        : !tables.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : tables.data.length === 0 ? <p className="gh-muted">{t('조건에 맞는 모델 TABLE 이 없습니다.')}</p>
        : (
          <ul className="source-nav-list source-picker-list">
            {tables.data.map((m) => {
              const taken = m.mappedSourceTableId != null && m.mappedSourceTableId !== sourceTableId;
              return (
                <li key={m.id}>
                  <button type="button" className="source-nav-item" disabled={taken} onClick={() => onPick(m.id)}>
                    <span><span className="gh-mono">{m.tableName}</span>{m.tableKorName && <span className="gh-muted"> · {m.tableKorName}</span>}</span>
                    {taken && <Label variant="attention">{t('이미 다른 원본에 매핑됨')}</Label>}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
      </div>
    </div>
  );
}
