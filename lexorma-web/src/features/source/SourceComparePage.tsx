import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { GitCompareArrows, RefreshCw, TriangleAlert } from 'lucide-react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, ApiError } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { dbTypeLabel } from './model';
import { Pager } from './shared';
import { SourceImportHistory, SourceImportPanel } from './SourceImportPanel';
import '../../styles/source.css';

/**
 * 원본·모델 비교 (M-4d) : 마지막 반영 수집의 원본과 Lexorma 물리 모델의 차이를 M-4c 의 확정 매핑 기준으로 보여 준다. <b>읽기 전용</b> —
 * 원본 DB 에 접속하지 않고, 물리 모델·매핑을 바꾸지 않으며, 가져오기 버튼이 없다 (M-4e). 비교 결과는 저장하지 않고 서버가 요청마다 계산한다.
 * 비교 기준(기준 연결·반영 순서)은 요약에서 받아 목록·상세 요청에 함께 보낸다 : 그 사이 바뀌면 서버가 409 로 알리고 화면을 새로 고치게 한다.
 */
type CompareStatus = 'SAME' | 'ADDED_IN_SOURCE' | 'ADDED_IN_MODEL' | 'MODIFIED' | 'RENAME_CANDIDATE' | 'CONFLICT' | 'UNKNOWN';
type AttributeResult = 'SAME' | 'DIFFERENT' | 'UNKNOWN' | 'NOT_COMPARED';
type ObjectKind = 'TABLE' | 'COLUMN' | 'PRIMARY_KEY' | 'UNIQUE' | 'CHECK' | 'FOREIGN_KEY' | 'INDEX';
type TableReason = 'COMPARED' | 'UNMAPPED' | 'MAPPING_CANDIDATE' | 'SCHEMA_UNMAPPED' | 'MODEL_NAME_TAKEN' | 'VIEW_NAME_TAKEN' | 'RECHECK' | 'MAPPING_CONFLICT'
  | 'SOURCE_REMOVED' | 'MODEL_DELETED' | 'MODEL_ONLY' | 'UNSUPPORTED_DBMS';
type Note = 'MODEL_HAS_NO_FIELD' | 'SOURCE_COMMENT' | 'GENERATED_NAME' | 'EXPRESSION' | 'UNSUPPORTED_TYPE' | 'DEFAULT_RULE' | 'SERIAL_DEFAULT' | 'UNRESOLVED_TARGET'
  | 'RELATIVE_ORDER' | 'NULLABLE_UNKNOWN';
type WarningCode = 'MODEL_TYPE_CONVERTED' | 'MODEL_TYPE_MISSING' | 'CONSTRAINT_INDEX' | 'DUPLICATE_MODEL_NAME' | 'UNSUPPORTED_DBMS';
type MappingStatus = 'UNMAPPED' | 'MAPPED' | 'RECHECK' | 'CONFLICT' | 'SOURCE_MISSING' | 'MODEL_MISSING';

interface Summary {
  base: { id: number; name: string; dbType: string; enabled: boolean; collectable: boolean } | null;
  reflectedOrder: number | null; executionId: number | null; reflectedAt: string | null; comparable: boolean; notComparable: string | null;
  sourceTables: number; viewCount: number; mappedTables: number; comparedTables: number; sameTables: number; differentTables: number; unknownTables: number;
  unmappedTables: number; modelOnlyTables: number; conflictTables: number; recheckTables: number; tables: Partial<Record<CompareStatus, number>>;
}
interface TableRow {
  key: string; sourceTableId: number | null; sourceSchemaId: number | null; sourceSchemaName: string | null; sourceTableName: string | null; sourceComment: string | null;
  mappingId: number | null; mappingStatus: MappingStatus | null; tableId: number | null; schemaName: string | null; tableName: string | null; tableKorName: string | null;
  status: CompareStatus; reason: TableReason; differences: number; unknowns: number; differencesByKind: Partial<Record<ObjectKind, number>>;
}
interface TablePage { connectionId: number | null; reflectedOrder: number | null; rows: TableRow[]; total: number; counts: Partial<Record<CompareStatus, number>> }
interface AttributeDiff { attribute: string; source: string | null; model: string | null; result: AttributeResult | null; note: Note | null }
interface ObjectDiff { kind: ObjectKind; sourceName: string | null; modelName: string | null; status: CompareStatus; attributes: AttributeDiff[]; candidates: string[]; ambiguous: boolean }
interface RenameCandidate {
  tableKey: string; sourceSchemaName: string | null; sourceTableName: string | null; tableName: string | null; kind: ObjectKind; sourceName: string; modelName: string;
  basis: string[]; signature: string | null; ambiguous: boolean;
}
interface TableDetail { table: TableRow; objects: ObjectDiff[]; renameCandidates: RenameCandidate[]; warnings: { code: WarningCode; args: string[] }[] }
interface RenamePage { rows: RenameCandidate[]; total: number }

const STATUS: Record<CompareStatus, { label: string; variant: LabelVariant }> = {
  SAME: { label: t('같음'), variant: 'success' },
  MODIFIED: { label: t('다름'), variant: 'attention' },
  ADDED_IN_SOURCE: { label: t('원본에만'), variant: 'accent' },
  ADDED_IN_MODEL: { label: t('모델에만'), variant: 'accent' },
  RENAME_CANDIDATE: { label: t('이름 변경 후보'), variant: 'attention' },
  CONFLICT: { label: t('충돌'), variant: 'danger' },
  UNKNOWN: { label: t('판단 불가'), variant: '' },
};
const STATUSES = Object.keys(STATUS) as CompareStatus[];

const RESULT: Record<AttributeResult, { label: string; variant: LabelVariant }> = {
  SAME: { label: t('같음'), variant: 'success' },
  DIFFERENT: { label: t('다름'), variant: 'attention' },
  UNKNOWN: { label: t('판단 불가'), variant: '' },
  NOT_COMPARED: { label: t('비교 안 함'), variant: 'done' },
};

const MAPPING: Record<MappingStatus, string> = {
  UNMAPPED: t('미매핑'), MAPPED: t('매핑됨'), RECHECK: t('재확인 필요'), CONFLICT: t('충돌'), SOURCE_MISSING: t('원본 없음'), MODEL_MISSING: t('모델 없음'),
};

const REASON: Record<TableReason, string> = {
  COMPARED: t('매핑된 TABLE 의 하위 객체까지 비교했습니다'),
  UNMAPPED: t('매핑되지 않았고 같은 이름의 모델 TABLE 이 없습니다'),
  MAPPING_CANDIDATE: t('같은 이름의 모델 TABLE 후보가 있지만 매핑이 확정되지 않아 비교하지 않았습니다'),
  SCHEMA_UNMAPPED: t('원본 스키마 매핑이 없거나 유효하지 않습니다'),
  MODEL_NAME_TAKEN: t('같은 이름의 모델 TABLE 이 이미 다른 원본에 매핑되어 있습니다'),
  VIEW_NAME_TAKEN: t('같은 이름의 모델 VIEW 가 있습니다'),
  RECHECK: t('원본이 다시 생겨 매핑 재확인이 필요합니다'),
  MAPPING_CONFLICT: t('매핑이 충돌합니다 (매핑 관리에서 확인)'),
  SOURCE_REMOVED: t('마지막 수집에서 원본이 사라졌습니다 (모델은 자동으로 지우지 않습니다)'),
  MODEL_DELETED: t('매핑된 모델 TABLE 이 삭제되었습니다'),
  MODEL_ONLY: t('매핑된 모델 스키마에 있지만 원본 TABLE 에 매핑되지 않았습니다'),
  UNSUPPORTED_DBMS: t('이 DBMS 의 비교 규칙이 아직 없습니다'),
};

const NOT_COMPARABLE: Record<string, string> = {
  NO_BASE: t('가져오기 기준 연결이 없습니다. 원본 매핑 관리에서 기준 연결을 정하세요.'),
  NO_COLLECTION: t('기준 연결에 완료된 수집이 없습니다. 먼저 메타데이터를 수집하세요.'),
  UNSUPPORTED_DBMS: t('이 DBMS 는 아직 비교 규칙이 없어 매핑된 TABLE 을 판단 불가로 표시합니다 (PostgreSQL 만).'),
};

const KIND: Record<ObjectKind, string> = {
  TABLE: 'TABLE', COLUMN: t('컬럼'), PRIMARY_KEY: 'PK', UNIQUE: 'UNIQUE', CHECK: 'CHECK', FOREIGN_KEY: 'FK', INDEX: t('인덱스'),
};
const KINDS = Object.keys(KIND) as ObjectKind[];

const ATTRIBUTE: Record<string, string> = {
  dataType: t('타입'), length: t('길이'), precision: t('정밀도'), scale: t('소수 자릿수'), nullable: t('NULL 허용'), autoIncrement: t('자동 증가'),
  default: t('기본값'), order: t('순서'), comment: t('원본 COMMENT'), columns: t('구성 컬럼'), name: t('이름'), expression: t('조건식'),
  referencedTable: t('참조 TABLE'), referencedColumns: t('참조 컬럼'), deleteRule: t('삭제 규칙'), updateRule: t('갱신 규칙'), unique: t('고유'),
  indexType: t('인덱스 유형'), keys: t('검색 키'), include: 'INCLUDE', predicate: t('부분 조건'),
};

const NOTE: Record<Note, string> = {
  MODEL_HAS_NO_FIELD: t('모델에 이 속성 칸이 없습니다'),
  SOURCE_COMMENT: t('원본 COMMENT 는 모델의 원본 COMMENT 칸과 비교합니다 (업무 설명과는 비교하지 않습니다)'),
  GENERATED_NAME: t('모델은 이 이름을 저장하지 않고 DDL 을 만들 때 정합니다'),
  EXPRESSION: t('식이 달라 의미가 같은지 판단하지 않았습니다'),
  UNSUPPORTED_TYPE: t('별칭 규칙을 모르는 타입이라 표기만 비교했습니다'),
  DEFAULT_RULE: t('모델에 규칙 칸이 없고 DDL 은 기본(NO ACTION)입니다'),
  SERIAL_DEFAULT: t('SERIAL 이 시퀀스 기본값을 만듭니다'),
  UNRESOLVED_TARGET: t('참조 대상 원본 TABLE 이 매핑되지 않아 견줄 수 없습니다'),
  RELATIVE_ORDER: t('양쪽 모두에 있는 컬럼 사이의 순서입니다'),
  NULLABLE_UNKNOWN: t('원본이 NULL 허용 여부를 주지 않았습니다'),
};

const AUTO: Record<string, string> = { SEQUENCE: t('시퀀스'), IDENTITY_ALWAYS: 'IDENTITY ALWAYS', IDENTITY_BY_DEFAULT: 'IDENTITY BY DEFAULT' };

function warningText(code: WarningCode, args: string[]) {
  switch (code) {
    case 'MODEL_TYPE_CONVERTED': return t('{0} : 모델 타입 {1} 은 PostgreSQL DDL 에서 {2} 로 만들어집니다.', args[0], args[1], args[2]);
    case 'MODEL_TYPE_MISSING': return t('{0} : 모델 타입이 없어 VARCHAR 로 비교했습니다.', args[0]);
    case 'CONSTRAINT_INDEX': return t('PK·UNIQUE 제약조건이 만든 원본 인덱스는 인덱스 비교에서 뺐습니다 : {0}', args[0]);
    case 'DUPLICATE_MODEL_NAME': return t('모델 TABLE 에 비교 이름이 같은 컬럼이 여럿입니다 : {0}', args[0]);
    case 'UNSUPPORTED_DBMS': return t('{0} 원본의 비교 규칙이 아직 없습니다.', dbTypeLabel(args[0]));
  }
}

const TABLE_PAGE = 50;
const KEY = 'source-compare';

function StatusLabel({ status }: { status: CompareStatus }) {
  return <Label variant={STATUS[status].variant} dot>{STATUS[status].label}</Label>;
}

function attributeValue(attribute: string, value: string | null) {
  if (value == null) return <span className="gh-muted">-</span>;
  return <span className="gh-mono source-break">{attribute === 'autoIncrement' ? AUTO[value] ?? value : value}</span>;
}

/** 409 : 비교 기준이 바뀌었다 (기준 연결 변경·새 수집) */
function isStale(error: unknown) {
  return error instanceof ApiError && error.code === 'SOURCE_COMPARE_STALE';
}

export function SourceComparePage() {
  const queryClient = useQueryClient();
  const summary = useQuery({ queryKey: [KEY], queryFn: () => api<Summary>('/api/sourceCompare', { silent: true }) });
  const data = summary.data;
  const refresh = () => queryClient.invalidateQueries({ queryKey: [KEY] });

  return (
    <>
      <PageHeader category={t('원본 DB 관리')} title={t('원본·모델 비교')}
        description={t('마지막으로 반영된 원본 수집과 Lexorma 물리 모델의 차이를 원본 매핑 관리에서 확정한 매핑 기준으로 보여 줍니다. 읽기 전용입니다 : 원본 DB 에 접속하지 않고, 물리 모델과 매핑을 바꾸지 않습니다.')} />
      {summary.isError ? <p role="alert">{t('비교 정보를 불러오지 못했습니다.')} {summary.error.message}</p>
        : !data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : (
          <>
            <section className="source-card" aria-label={t('비교 요약')}>
              <h3 className="source-title-icon"><GitCompareArrows size={18} aria-hidden="true" /> {t('비교 요약')}
                <button type="button" className="btn btn-sm source-title-action" onClick={() => void refresh()}><RefreshCw size={14} aria-hidden="true" /> {t('새로 고침')}</button>
              </h3>
              {data.base && (
                <dl className="source-dl source-dl-grid">
                  <dt>{t('기준 연결')}</dt><dd>{data.base.name} · {dbTypeLabel(data.base.dbType)}</dd>
                  <dt>{t('비교 시점')}</dt>
                  <dd>{data.reflectedOrder != null ? t('반영 순서 {0} · 수집 #{1} · {2}', data.reflectedOrder, data.executionId, formatDateTime(data.reflectedAt)) : '-'}</dd>
                </dl>
              )}
              {data.notComparable && (
                <p className="source-warn" role="status">
                  {NOT_COMPARABLE[data.notComparable] ?? data.notComparable}{' '}
                  {data.notComparable === 'NO_BASE' && <Link to="/sourceMapping">{t('원본 매핑 관리')}</Link>}
                  {data.notComparable === 'NO_COLLECTION' && data.base && <Link to={`/sourceCollection?connectionId=${data.base.id}`}>{t('메타데이터 수집으로 이동')}</Link>}
                </p>
              )}
              {data.reflectedOrder != null && (
                <div className="source-compare-tiles">
                  <Tile label={t('원본 TABLE')} value={data.sourceTables} />
                  <Tile label={t('비교한 TABLE')} value={data.comparedTables} />
                  <Tile label={t('같음')} value={data.sameTables} />
                  <Tile label={t('차이 있음')} value={data.differentTables} emphasis={data.differentTables > 0} />
                  <Tile label={t('판단 불가')} value={data.unknownTables} />
                  <Tile label={t('미매핑 원본')} value={data.unmappedTables} />
                  <Tile label={t('모델에만')} value={data.modelOnlyTables} />
                  <Tile label={t('충돌')} value={data.conflictTables} emphasis={data.conflictTables > 0} />
                  <Tile label={t('재확인 필요')} value={data.recheckTables} emphasis={data.recheckTables > 0} />
                </div>
              )}
              {data.reflectedOrder != null && (
                <p className="form-help">
                  {t('VIEW {0}개는 비교하지 않습니다 (정의 SQL 을 수집하지 않음). 판단 불가는 같음이 아니며 삭제 대상도 아닙니다. 비교 안 함은 모델에 그 속성이 없거나 모델이 이름을 만들어 쓰는 경우입니다.', data.viewCount)}
                </p>
              )}
            </section>
            {data.base && data.reflectedOrder != null && (
              <>
                <CompareTables summary={data} onStale={refresh} />
                <RenameCandidates summary={data} onStale={refresh} />
                <SourceImportHistory />
              </>
            )}
          </>
        )}
    </>
  );
}

function Tile({ label, value, emphasis }: { label: string; value: number; emphasis?: boolean }) {
  return (
    <div className={`source-compare-tile${emphasis ? ' is-emphasis' : ''}`}>
      <span className="gh-muted">{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Stale({ onStale }: { onStale: () => void }) {
  return (
    <p role="alert" className="source-warn">
      <TriangleAlert size={14} aria-hidden="true" /> {t('기준 연결이 바뀌었거나 새 수집이 반영되었습니다.')}{' '}
      <button type="button" className="btn btn-sm" onClick={onStale}>{t('새로 고침')}</button>
    </p>
  );
}

function basisParams(summary: Summary) {
  return { connectionId: String(summary.base!.id), reflectedOrder: String(summary.reflectedOrder) };
}

function CompareTables({ summary, onStale }: { summary: Summary; onStale: () => void }) {
  const dialog = useDialog();
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(keyword.trim()); setPage(0); }, 300);
    return () => clearTimeout(timer);
  }, [keyword]);
  const params = new URLSearchParams({ ...basisParams(summary), page: String(page), size: String(TABLE_PAGE) });
  if (status) params.set('status', status);
  if (query) params.set('keyword', query);
  const tables = useQuery({
    queryKey: [KEY, 'tables', summary.base!.id, summary.reflectedOrder, status, query, page],
    queryFn: () => api<TablePage>(`/api/sourceCompare/tables?${params}`, { silent: true }),
    retry: false,
  });

  const open = (row: TableRow) => {
    const title = row.sourceTableName != null ? `${row.sourceSchemaName}.${row.sourceTableName}` : `${row.schemaName}.${row.tableName}`;
    dialog.open({
      title: t('TABLE 비교 · {0}', title),
      className: 'dialog-xl',
      content: <CompareDetail summary={summary} rowKey={row.key} onStale={onStale} />,
    });
  };

  return (
    <section className="source-card" aria-label={t('TABLE 비교')}>
      <h3>{t('TABLE 비교')}</h3>
      <div className="source-mapping-filter">
        <select className="form-control" aria-label={t('비교 상태')} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">{t('전체 상태')}</option>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS[s].label}{tables.data?.counts[s] ? ` (${tables.data.counts[s]})` : ''}</option>)}
        </select>
        <input type="search" className="form-control" aria-label={t('TABLE 이름 검색')} placeholder={t('원본·모델 TABLE 이름 검색')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      </div>
      {tables.isError ? (isStale(tables.error) ? <Stale onStale={onStale} /> : <p role="alert">{t('TABLE 비교를 불러오지 못했습니다.')} {tables.error.message}</p>)
        : !tables.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : tables.data.rows.length === 0 ? <p className="gh-muted">{t('조건에 맞는 TABLE 이 없습니다.')}</p>
        : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('원본 TABLE')}</th><th>{t('모델 TABLE')}</th><th>{t('매핑')}</th><th>{t('비교 결과')}</th><th className="text-end">{t('차이')}</th><th>{t('작업')}</th></tr></thead>
                <tbody>
                  {tables.data.rows.map((row) => (
                    <tr key={row.key}>
                      <td className="source-break">
                        {row.sourceTableName != null ? <span className="gh-mono">{row.sourceSchemaName}.{row.sourceTableName}</span> : <span className="gh-muted">-</span>}
                        {row.sourceComment && <div className="gh-muted source-sub">{row.sourceComment}</div>}
                      </td>
                      <td className="source-break">
                        {row.tableName != null ? <span className="gh-mono">{row.schemaName}.{row.tableName}</span> : <span className="gh-muted">-</span>}
                        {row.tableKorName && row.tableKorName !== row.tableName && <div className="gh-muted source-sub">{row.tableKorName}</div>}
                      </td>
                      <td>{row.mappingStatus ? <Label>{MAPPING[row.mappingStatus]}</Label> : <span className="gh-muted">-</span>}</td>
                      <td>
                        <span className="source-mapping-status">
                          <StatusLabel status={row.status} />
                          {row.reason !== 'COMPARED' && <span className="gh-muted source-sub">{REASON[row.reason]}</span>}
                        </span>
                      </td>
                      <td className="text-end">
                        {row.reason === 'COMPARED' ? (
                          <>
                            {row.differences}
                            {row.unknowns > 0 && <div className="gh-muted source-sub">{t('판단 불가 {0}', row.unknowns)}</div>}
                            {row.differences > 0 && (
                              <div className="gh-muted source-sub">{KINDS.filter((k) => row.differencesByKind[k]).map((k) => `${KIND[k]} ${row.differencesByKind[k]}`).join(' · ')}</div>
                            )}
                          </>
                        ) : <span className="gh-muted">-</span>}
                      </td>
                      <td><button type="button" className="btn btn-sm" onClick={() => open(row)}>{t('상세')}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} size={TABLE_PAGE} total={tables.data.total} onPage={setPage} />
          </>
        )}
      <p className="form-help">{t('미매핑 TABLE 은 임의로 이어 보지 않습니다. 매핑은 원본 매핑 관리에서 확정합니다. 가져오기는 TABLE 상세에서 항목을 골라 미리보기한 뒤 확정합니다.')}</p>
    </section>
  );
}

function CompareDetail({ summary, rowKey, onStale }: { summary: Summary; rowKey: string; onStale: () => void }) {
  const [kind, setKind] = useState<ObjectKind | ''>('');
  const [onlyDifferent, setOnlyDifferent] = useState(false);
  const params = new URLSearchParams({ ...basisParams(summary), key: rowKey });
  const detail = useQuery({
    queryKey: [KEY, 'detail', summary.base!.id, summary.reflectedOrder, rowKey],
    queryFn: () => api<TableDetail>(`/api/sourceCompare/table?${params}`, { silent: true }),
    retry: false,
  });
  if (detail.isError) return isStale(detail.error) ? <Stale onStale={onStale} /> : <p role="alert">{detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const { table, objects, renameCandidates, warnings } = detail.data;
  const shown = objects.filter((o) => (!kind || o.kind === kind) && (!onlyDifferent || o.status !== 'SAME'));

  return (
    <div className="source-compare-detail">
      <div className="source-compare-head">
        <StatusLabel status={table.status} />
        {table.reason !== 'COMPARED' && <span className="gh-muted">{REASON[table.reason]}</span>}
        {table.reason === 'COMPARED' && <span className="gh-muted">{t('차이 {0} · 판단 불가 {1}', table.differences, table.unknowns)}</span>}
      </div>
      {warnings.length > 0 && (
        <ul className="source-compare-warnings">
          {warnings.map((w, i) => <li key={i}><TriangleAlert size={14} aria-hidden="true" /> {warningText(w.code, w.args)}</li>)}
        </ul>
      )}
      {table.reason !== 'COMPARED' ? (
        <p className="gh-muted">{t('매핑이 유효한 TABLE 만 컬럼·제약조건·인덱스를 비교합니다.')}</p>
      ) : (
        <>
          <div className="source-mapping-filter">
            <select className="form-control" aria-label={t('객체 종류')} value={kind} onChange={(e) => setKind(e.target.value as ObjectKind | '')}>
              <option value="">{t('전체 객체')}</option>
              {KINDS.map((k) => <option key={k} value={k}>{KIND[k]} ({objects.filter((o) => o.kind === k).length})</option>)}
            </select>
            <label className="source-check">
              <input type="checkbox" checked={onlyDifferent} onChange={(e) => setOnlyDifferent(e.target.checked)} /> {t('같음 숨기기')}
            </label>
          </div>
          {shown.length === 0 ? <p className="gh-muted">{t('조건에 맞는 객체가 없습니다.')}</p> : (
            <div className="source-table-wrap">
              <table className="source-table source-compare-objects">
                <thead><tr><th>{t('객체')}</th><th>{t('속성')}</th><th>{t('원본 값')}</th><th>{t('모델 값')}</th><th>{t('비교 결과')}</th></tr></thead>
                {shown.map((o, i) => <ObjectRows key={`${o.kind}-${o.sourceName}-${o.modelName}-${i}`} object={o} />)}
              </table>
            </div>
          )}
          {renameCandidates.length > 0 && (
            <>
              <h4>{t('이름 변경 후보')}</h4>
              <p className="form-help">{t('이름은 다르지만 구조가 같은 원본에만·모델에만 있는 객체입니다. 제안일 뿐이며 저장하거나 같은 객체로 확정하지 않습니다.')}</p>
              <RenameTable rows={renameCandidates} showTable={false} />
            </>
          )}
        </>
      )}
      {table.sourceTableId != null && <SourceImportPanel rowKey={rowKey} connectionId={summary.base!.id} reflectedOrder={summary.reflectedOrder!} onStale={onStale} />}
    </div>
  );
}

function ObjectRows({ object }: { object: ObjectDiff }) {
  const name = (
    <>
      <Label>{KIND[object.kind]}</Label>{' '}
      <span className="gh-mono source-break">{object.sourceName ?? '-'}</span>
      <span className="gh-muted"> ↔ </span>
      <span className="gh-mono source-break">{object.modelName ?? '-'}</span>
      <div className="source-sub"><StatusLabel status={object.status} />{object.ambiguous && <> <Label variant="attention">{t('모호함')}</Label></>}</div>
      {object.candidates.length > 0 && <div className="gh-muted source-sub">{t('후보 : {0}', object.candidates.join(', '))}</div>}
    </>
  );
  const attributes = object.attributes.length ? object.attributes : [null];
  return (
    <tbody className={`source-compare-object is-${object.status.toLowerCase()}`}>
      {attributes.map((a, i) => (
        <tr key={a?.attribute ?? i}>
          {i === 0 && <td rowSpan={attributes.length}>{name}</td>}
          <td>{a ? ATTRIBUTE[a.attribute] ?? a.attribute : '-'}</td>
          <td>{a ? attributeValue(a.attribute, a.source) : '-'}</td>
          <td>{a ? attributeValue(a.attribute, a.model) : '-'}</td>
          <td>
            {a?.result ? <Label variant={RESULT[a.result].variant}>{RESULT[a.result].label}</Label> : <span className="gh-muted">-</span>}
            {a?.note && <div className="gh-muted source-sub">{NOTE[a.note]}</div>}
          </td>
        </tr>
      ))}
    </tbody>
  );
}

function RenameTable({ rows, showTable }: { rows: RenameCandidate[]; showTable: boolean }) {
  return (
    <div className="source-table-wrap">
      <table className="source-table">
        <thead>
          <tr>
            {showTable && <th>{t('TABLE')}</th>}
            <th>{t('객체')}</th><th>{t('원본 이름')}</th><th>{t('모델 이름')}</th><th>{t('판단 근거')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={`${r.tableKey}-${r.kind}-${r.sourceName}-${r.modelName}-${i}`}>
              {showTable && <td className="gh-mono source-break">{r.sourceSchemaName}.{r.sourceTableName} ↔ {r.tableName}</td>}
              <td><Label>{KIND[r.kind]}</Label></td>
              <td className="gh-mono source-break">{r.sourceName}</td>
              <td className="gh-mono source-break">{r.modelName}{r.ambiguous && <> <Label variant="attention">{t('모호함')}</Label></>}</td>
              <td>
                <span className="gh-muted">{t('같은 {0}', r.basis.map((b) => ATTRIBUTE[b] ?? b).join('·'))}</span>
                {r.signature && <div className="gh-mono source-sub source-break">{r.signature}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function RenameCandidates({ summary, onStale }: { summary: Summary; onStale: () => void }) {
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ ...basisParams(summary), page: String(page), size: String(TABLE_PAGE) });
  const renames = useQuery({
    queryKey: [KEY, 'renames', summary.base!.id, summary.reflectedOrder, page],
    queryFn: () => api<RenamePage>(`/api/sourceCompare/renameCandidates?${params}`, { silent: true }),
    retry: false,
  });
  return (
    <section className="source-card" aria-label={t('이름 변경 후보')}>
      <h3>{t('이름 변경 후보')}</h3>
      {renames.isError ? (isStale(renames.error) ? <Stale onStale={onStale} /> : <p role="alert">{renames.error.message}</p>)
        : !renames.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : renames.data.rows.length === 0 ? <p className="gh-muted">{t('이름 변경 후보가 없습니다.')}</p>
        : (
          <>
            <RenameTable rows={renames.data.rows} showTable />
            <Pager page={page} size={TABLE_PAGE} total={renames.data.total} onPage={setPage} />
          </>
        )}
      <p className="form-help">{t('후보는 제안일 뿐입니다. 저장하지 않고, 비교 결과를 같음으로 바꾸지 않습니다. 원본 TABLE 의 이름 변경은 삭제 + 새로 생김으로 봅니다.')}</p>
    </section>
  );
}
