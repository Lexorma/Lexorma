import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import {
  CHANGE_TYPE, CONSTRAINT_TYPE, fromDetail, fromSnapshot,
  type CatalogAt, type CatalogTableDetail, type Execution, type PageResult, type SourceChange, type SourceConnection, type TableState, type TableVersion, type ViewRelation, type ViewTable,
} from './model';
import { ChangeTable } from './SourceCollectionPage';
import { ConnectionPicker, NoConnection, orDash, useSelectedConnection, useSourceConnections } from './shared';
import '../../styles/source.css';

type Tab = 'columns' | 'constraints' | 'indexes' | 'relations' | 'changes' | 'versions';

/**
 * 원본 카탈로그 (M-4a) : 수집해 둔 원본 DB 의 스키마 → 테이블·VIEW → 컬럼·PK·UNIQUE·CHECK·인덱스·FK·COMMENT 조회와 수집 시점(반영 순서) 선택.
 * 모두 Lexorma 에 저장된 것을 읽는다 (원본 DB 에 접속하지 않는다). 원본 카탈로그는 물리 모델과 별개이며 이 화면은 물리 모델을 바꾸지 않는다.
 * - 스키마·테이블 목록 : 그 시점의 스냅샷 (order 가 없으면 마지막 반영)
 * - 테이블 상세 : 마지막 반영이면 현재 카탈로그(들어오는 FK 포함), 과거 시점이면 그때의 버전 내용
 * VIEW 는 이름·컬럼·COMMENT 만 있다 (정의 SQL 은 수집하지 않는다).
 */
export function SourceCatalogPage() {
  const connections = useSourceConnections();
  const { selected, select, searchParams, setSearchParams } = useSelectedConnection(connections.data);

  return (
    <>
      <PageHeader category={t('원본 DB 관리')} title={t('원본 카탈로그')}
        description={t('원본 DB 에서 수집한 스키마·테이블·컬럼·제약조건·인덱스·FK 를 조회합니다. 원본 카탈로그는 수집한 사실이며 Lexorma 물리 모델과 따로 저장됩니다. 조회할 때 원본 DB 에 접속하지 않습니다.')} />
      {connections.isError ? <p role="alert">{t('연결 목록을 불러오지 못했습니다.')} {connections.error.message}</p>
        : connections.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : !selected ? <NoConnection />
        : <CatalogBrowser key={selected.id} connections={connections.data} connection={selected} onSelectConnection={select} params={searchParams} setParams={setSearchParams} />}
    </>
  );
}

function CatalogBrowser({ connections, connection, onSelectConnection, params, setParams }: {
  connections: SourceConnection[];
  connection: SourceConnection;
  onSelectConnection: (id: number) => void;
  params: URLSearchParams;
  setParams: ReturnType<typeof useSelectedConnection>['setSearchParams'];
}) {
  const order = Number(params.get('order')) || null;
  const schemaParam = Number(params.get('schemaId')) || null;
  const tableId = Number(params.get('tableId')) || null;
  const [type, setType] = useState('');
  const [keyword, setKeyword] = useState('');

  const update = (values: Record<string, string | number | null>) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    Object.entries(values).forEach(([key, value]) => (value == null || value === '' ? next.delete(key) : next.set(key, String(value))));
    return next;
  });

  // 고를 수 있는 시점 : 반영된(COMPLETED) 수집. 실패·취소한 수집은 반영 순서가 없어 고를 수 없다
  const reflected = useQuery({
    queryKey: ['source-reflected', connection.id],
    queryFn: () => api<PageResult<Execution>>(`/api/source/connections/${enc(connection.id)}/collections?page=0&size=200`, { silent: true }),
    select: (page) => page.data.filter((e) => e.status === 'COMPLETED' && e.reflectedOrder != null),
  });
  const orderQuery = order ? `?order=${enc(order)}` : '';
  const catalog = useQuery({
    queryKey: ['source-catalog-at', connection.id, order],
    queryFn: () => api<CatalogAt>(`/api/source/connections/${enc(connection.id)}/snapshot${orderQuery}`, { silent: true }),
  });
  const schemas = catalog.data?.schemas ?? [];
  const schemaId = schemas.find((s) => s.schemaId === schemaParam)?.schemaId ?? schemas[0]?.schemaId ?? null;
  const tables = useQuery({
    queryKey: ['source-tables-at', connection.id, schemaId, order],
    queryFn: () => api<TableState[]>(`/api/source/connections/${enc(connection.id)}/snapshot/schemas/${enc(schemaId)}/tables${orderQuery}`, { silent: true }),
    enabled: schemaId != null,
  });
  const word = keyword.trim().toLowerCase();
  const visibleTables = (tables.data ?? []).filter((tb) => (!type || tb.type === type) && (!word || tb.name.toLowerCase().includes(word)));
  // 마지막 반영을 고른 것과 같으면 현재 카탈로그로 상세를 본다 (들어오는 FK·원본 표기 포함)
  const maxOrder = Math.max(0, ...(reflected.data ?? []).map((e) => e.reflectedOrder ?? 0));
  const latest = order == null || order === maxOrder;

  return (
    <>
      <div className="source-catalog-bar">
        <ConnectionPicker connections={connections} selected={connection} onSelect={onSelectConnection} />
        <div className="form-group source-picker">
          <label htmlFor="sourceCatalogOrder">{t('수집 시점')}</label>
          <select id="sourceCatalogOrder" className="form-control" value={order ?? ''} onChange={(e) => update({ order: e.target.value || null, schemaId: null, tableId: null })}>
            <option value="">{t('마지막 반영 (현재)')}</option>
            {(reflected.data ?? []).map((e) => (
              <option key={e.id} value={e.reflectedOrder ?? ''}>{t('반영 순서 {0} · 수집 #{1} · {2}', e.reflectedOrder, e.id, formatDateTime(e.reflectedAt))}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="gh-flash source-flash" role="note">
        <span>
          {catalog.data?.reflectedOrder != null
            ? t('반영 순서 {0} (수집 #{1}, {2}) 시점에 Lexorma 가 확인해 둔 원본 구조입니다. 원본 DB 를 그 시점으로 되돌린 것이 아니며, Lexorma 물리 모델과는 별개입니다.', catalog.data.reflectedOrder, catalog.data.executionId, formatDateTime(catalog.data.reflectedAt))
            : t('원본 카탈로그는 수집한 사실이며 Lexorma 물리 모델과는 별개입니다.')}
        </span>
      </div>

      {catalog.isError ? <p role="alert">{t('원본 카탈로그를 불러오지 못했습니다.')} {catalog.error.message}</p>
        : catalog.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : catalog.data.reflectedOrder == null ? (
          <div className="source-empty" role="status">
            <p>{t('아직 반영된 수집이 없습니다.')}</p>
            <p className="gh-muted">{t('메타데이터 수집 화면에서 수집을 실행하세요. 완료된 수집만 여기에 보입니다.')}</p>
          </div>
        ) : (
          <div className="source-browser">
            <section className="source-card source-schema-pane" aria-label={t('스키마')}>
              <h3>{t('스키마 ({0})', schemas.length)}</h3>
              {schemas.length === 0 ? <p className="gh-muted">{t('스키마가 없습니다.')}</p> : (
                <ul className="source-nav-list">
                  {schemas.map((s) => (
                    <li key={s.schemaId}>
                      <button type="button" className={`source-nav-item${s.schemaId === schemaId ? ' is-active' : ''}`} aria-current={s.schemaId === schemaId ? 'true' : undefined}
                        onClick={() => update({ schemaId: s.schemaId, tableId: null })}>
                        <span className="gh-mono">{s.schemaName}</span>
                        <ChangeBadge changeType={s.changeType} versionOrder={s.versionOrder} order={catalog.data.reflectedOrder} />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="source-card source-table-pane" aria-label={t('테이블·VIEW')}>
              <h3>{t('테이블·VIEW ({0})', visibleTables.length)}</h3>
              <div className="source-filter">
                <select className="form-control" aria-label={t('유형')} value={type} onChange={(e) => setType(e.target.value)}>
                  <option value="">{t('전체 유형')}</option>
                  <option value="TABLE">TABLE</option>
                  <option value="VIEW">VIEW</option>
                </select>
                <input type="search" className="form-control" aria-label={t('이름 검색')} placeholder={t('이름 검색')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
              </div>
              {tables.isError ? <p role="alert">{t('테이블 목록을 불러오지 못했습니다.')} {tables.error.message}</p>
                : schemaId != null && tables.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
                : visibleTables.length === 0 ? <p className="gh-muted">{word || type ? t('조건에 맞는 테이블이 없습니다.') : t('테이블이 없습니다.')}</p>
                : (
                  <ul className="source-nav-list">
                    {visibleTables.map((tb) => (
                      <li key={tb.tableId}>
                        <button type="button" className={`source-nav-item${tb.tableId === tableId ? ' is-active' : ''}`} aria-current={tb.tableId === tableId ? 'true' : undefined}
                          onClick={() => update({ tableId: tb.tableId })}>
                          <span className="gh-mono source-break">{tb.name}</span>
                          <span className="source-labels">
                            {tb.type === 'VIEW' && <Label variant="done">VIEW</Label>}
                            <ChangeBadge changeType={tb.changeType} versionOrder={tb.versionOrder} order={catalog.data.reflectedOrder} />
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
            </section>

            <section className="source-card source-detail-pane" aria-label={t('상세')}>
              {tableId ? <TableDetailView key={`${tableId}-${order ?? 'latest'}`} tableId={tableId} order={latest ? null : order}
                onOpenTable={(id) => update({ tableId: id })} onOpenOrder={(o) => update({ order: o, tableId })} />
                : <p className="gh-muted">{t('테이블·VIEW 를 고르면 컬럼·제약조건·인덱스·FK 가 보입니다.')}</p>}
            </section>
          </div>
        )}
    </>
  );
}

/** 그 시점에 새로 생기거나 바뀐 객체 표시 (그 반영에서 생긴 버전일 때만) */
function ChangeBadge({ changeType, versionOrder, order }: { changeType: string; versionOrder: number; order: number | null }) {
  if (versionOrder !== order || changeType === 'BASE') return null;
  const type = CHANGE_TYPE[changeType as keyof typeof CHANGE_TYPE];
  return type ? <Label variant={type.variant}>{type.label}</Label> : null;
}

export function TableDetailView({ tableId, order, onOpenTable, onOpenOrder }: {
  tableId: number;
  /** null 이면 현재 카탈로그 */
  order: number | null;
  onOpenTable: (tableId: number) => void;
  onOpenOrder: (order: number) => void;
}) {
  const [tab, setTab] = useState<Tab>('columns');
  const detail = useQuery({
    queryKey: ['source-table', tableId, order],
    queryFn: async (): Promise<{ view: ViewTable; schemaName: string | null; present: boolean }> => {
      if (order == null) {
        const d = await api<CatalogTableDetail>(`/api/source/tables/${enc(tableId)}`, { silent: true });
        return { view: fromDetail(d), schemaName: d.schema.schemaName, present: d.table.present };
      }
      const snapshot = await api<{ content: unknown }>(`/api/source/tables/${enc(tableId)}/snapshot?order=${enc(order)}`, { silent: true });
      return { view: fromSnapshot(snapshot.content), schemaName: null, present: true };
    },
  });
  const changes = useQuery({
    queryKey: ['source-table-changes', tableId],
    queryFn: () => api<SourceChange[]>(`/api/source/tables/${enc(tableId)}/changes`, { silent: true }),
    enabled: tab === 'changes',
  });
  const versions = useQuery({
    queryKey: ['source-table-versions', tableId],
    queryFn: () => api<TableVersion[]>(`/api/source/tables/${enc(tableId)}/versions`, { silent: true }),
    enabled: tab === 'versions',
  });

  const pk = useMemo(() => new Set((detail.data?.view.constraints ?? []).filter((c) => c.type === 'PRIMARY_KEY').flatMap((c) => c.columns.map((n) => n.toLowerCase()))), [detail.data]);

  if (detail.isError) return <p role="alert">{t('테이블을 불러오지 못했습니다.')} {detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const { view } = detail.data;
  const isView = view.type === 'VIEW';
  const keys = view.constraints.filter((c) => c.type !== 'CHECK');
  const checks = view.constraints.filter((c) => c.type === 'CHECK');

  const tabs: [Tab, string][] = [
    ['columns', t('컬럼 ({0})', view.columns.length)],
    ...(!isView ? [
      ['constraints', t('제약조건 ({0})', view.constraints.length)] as [Tab, string],
      ['indexes', t('인덱스 ({0})', view.indexes.length)] as [Tab, string],
      ['relations', t('FK ({0})', view.relations.length + (view.referencedBy?.length ?? 0))] as [Tab, string],
    ] : []),
    ['changes', t('변경 이력')],
    ['versions', t('버전')],
  ];

  return (
    <div className="source-detail">
      <div className="source-exec-title">
        <h3 className="gh-mono source-break">{detail.data.schemaName ? `${detail.data.schemaName}.` : ''}{view.name}</h3>
        <Label variant={isView ? 'done' : 'accent'}>{isView ? 'VIEW' : 'TABLE'}</Label>
        {!detail.data.present && <Label variant="danger">{t('원본에서 사라짐')}</Label>}
        {order != null && <Label variant="attention">{t('반영 순서 {0} 시점', order)}</Label>}
      </div>
      <p className={view.comment ? 'source-comment' : 'gh-muted'}>{view.comment ?? t('원본 COMMENT 없음')}</p>
      {isView && <p className="form-help">{t('원본 VIEW 는 이름·컬럼·COMMENT 만 수집합니다. 정의 SQL 은 수집하지 않습니다.')}</p>}

      <ul className="nav nav-tabs">
        {tabs.map(([id, label]) => (
          <li className="nav-item" key={id}>
            <a href={`#source-${id}`} className={`nav-link${tab === id ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab(id); }}>{label}</a>
          </li>
        ))}
      </ul>

      {tab === 'columns' && (
        view.columns.length === 0 ? <p className="gh-muted">{t('컬럼이 없습니다.')}</p> : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th className="text-center">#</th><th>{t('컬럼명')}</th><th>{t('데이터 타입')}</th><th className="text-center">NULL</th><th>{t('기본값')}</th><th>{t('원본 COMMENT')}</th></tr></thead>
              <tbody>
                {view.columns.map((c, i) => (
                  <tr key={c.name}>
                    <td className="text-center">{c.ordinal ?? i + 1}</td>
                    <td className="gh-mono source-break">{c.name}{pk.has(c.name.toLowerCase()) && <> <Label variant="accent">PK</Label></>}</td>
                    <td className="gh-mono source-break">{c.dataType}</td>
                    <td className="text-center">{c.nullable == null ? '-' : c.nullable ? 'Y' : <strong>N</strong>}</td>
                    <td className="gh-mono source-break">{orDash(c.defaultValue)}</td>
                    <td>{c.comment ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {tab === 'constraints' && (
        view.constraints.length === 0 ? <p className="gh-muted">{t('제약조건이 없습니다.')}</p> : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th>{t('유형')}</th><th>{t('이름')}</th><th>{t('컬럼')}</th><th>{t('조건')}</th></tr></thead>
              <tbody>
                {[...keys, ...checks].map((c, i) => (
                  <tr key={`${c.type}-${c.name ?? i}`}>
                    <td><Label variant={c.type === 'PRIMARY_KEY' ? 'accent' : ''}>{CONSTRAINT_TYPE[c.type] ?? c.type}</Label></td>
                    <td className="gh-mono source-break">{orDash(c.name)}</td>
                    <td className="gh-mono source-break">{c.columns.join(', ') || '-'}</td>
                    <td className="gh-mono source-break">{orDash(c.checkExpression)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {tab === 'indexes' && (
        view.indexes.length === 0 ? <p className="gh-muted">{t('인덱스가 없습니다.')}</p> : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th>{t('이름')}</th><th>{t('방식')}</th><th className="text-center">UNIQUE</th><th>{t('구성 (순서대로)')}</th><th>{t('부분 인덱스 조건')}</th></tr></thead>
              <tbody>
                {view.indexes.map((idx, i) => (
                  <tr key={idx.name ?? i}>
                    <td className="gh-mono source-break">{orDash(idx.name)}</td>
                    <td className="gh-mono">{orDash(idx.indexType)}</td>
                    <td className="text-center">{idx.unique ? 'Y' : ''}</td>
                    <td>
                      <ol className="source-members">
                        {idx.members.map((m, k) => (
                          <li key={k} className="gh-mono source-break">
                            {m.sort === 'INCLUDE' ? <Label>INCLUDE</Label> : null} {m.expression ? <span title={t('표현식')}>({m.text})</span> : m.text}
                            {m.sort && m.sort !== 'INCLUDE' && <span className="gh-muted"> {m.sort}</span>}
                          </li>
                        ))}
                      </ol>
                    </td>
                    <td className="gh-mono source-break">{orDash(idx.predicate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {tab === 'relations' && (
        <>
          <h4>{t('이 테이블이 참조하는 FK ({0})', view.relations.length)}</h4>
          <RelationTable relations={view.relations} incoming={false} live={order == null} onOpenTable={onOpenTable} />
          {view.referencedBy ? (
            <>
              <h4>{t('이 테이블을 참조하는 FK ({0})', view.referencedBy.length)}</h4>
              <RelationTable relations={view.referencedBy} incoming live onOpenTable={onOpenTable} />
            </>
          ) : <p className="form-help">{t('과거 시점에서는 이 테이블을 참조하는 FK 를 보여 주지 않습니다 (버전은 테이블 자신의 내용만 담습니다).')}</p>}
        </>
      )}

      {tab === 'changes' && (
        changes.isError ? <p role="alert">{t('변경 이력을 불러오지 못했습니다.')} {changes.error.message}</p>
          : !changes.data ? <p role="status">{t('불러오는 중입니다.')}</p>
          : changes.data.length === 0 ? <p className="gh-muted">{t('변경 이력이 없습니다.')}</p>
          : <ChangeTable changes={changes.data} showOrder />
      )}

      {tab === 'versions' && (
        versions.isError ? <p role="alert">{t('버전을 불러오지 못했습니다.')} {versions.error.message}</p>
          : !versions.data ? <p role="status">{t('불러오는 중입니다.')}</p>
          : versions.data.length === 0 ? <p className="gh-muted">{t('버전이 없습니다.')}</p>
          : (
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('반영 순서')}</th><th>{t('수집')}</th><th>{t('구분')}</th><th>{t('생성 차수')}</th><th></th></tr></thead>
                <tbody>
                  {versions.data.map((v) => {
                    const type = CHANGE_TYPE[v.changeType] ?? { label: v.changeType, variant: '' as const };
                    return (
                      <tr key={v.id}>
                        <td>{v.reflectedOrder}</td>
                        <td>#{v.executionId}</td>
                        <td><Label variant={type.variant}>{type.label}</Label></td>
                        <td>{v.creationCycle}</td>
                        <td>{v.changeType !== 'REMOVED' && <button type="button" className="btn btn-sm" onClick={() => onOpenOrder(v.reflectedOrder)}>{t('이 시점 보기')}</button>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
      )}
    </div>
  );
}

/** live : 현재 카탈로그 (참조되는 테이블의 ID 가 있어 이동할 수 있다). 과거 버전에는 이름만 있다 */
function RelationTable({ relations, incoming, live, onOpenTable }: { relations: ViewRelation[]; incoming: boolean; live: boolean; onOpenTable: (tableId: number) => void }) {
  if (relations.length === 0) return <p className="gh-muted">{t('없습니다.')}</p>;
  return (
    <div className="source-table-wrap">
      <table className="source-table">
        <thead><tr><th>{t('이름')}</th><th>{incoming ? t('참조하는 테이블') : t('참조 테이블')}</th><th>{t('컬럼')}</th><th>{t('삭제·수정 규칙')}</th></tr></thead>
        <tbody>
          {relations.map((r, i) => {
            const target = `${r.otherSchema ? `${r.otherSchema}.` : ''}${r.otherTable}`;
            return (
              <tr key={r.name ?? i}>
                <td className="gh-mono source-break">{orDash(r.name)}</td>
                <td className="gh-mono source-break">
                  {r.otherTableId ? <button type="button" className="source-link-button" onClick={() => onOpenTable(r.otherTableId!)}>{target}</button> : target}
                  {live && !incoming && !r.otherTableId && <div className="gh-muted source-sub">{t('수집 범위 밖')}</div>}
                </td>
                <td className="gh-mono source-break">{r.columns.map((c) => `${c.column} → ${c.referencedColumn}`).join(', ')}</td>
                <td className="gh-mono">{orDash(r.deleteRule)} / {orDash(r.updateRule)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
