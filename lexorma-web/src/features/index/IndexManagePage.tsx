import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, DateTime, Label, Mono, User } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t, tc } from '../../lib/i18n';
import { useSchemas } from '../schema/schemas';
import { TableDdlView } from '../table/TableDdl';
import { useTableDetail } from '../table/tableDetail';
import { INDEX_GRID_MIN_WIDTH, indexColumns } from './columns';
import { IndexForm } from './IndexForm';
import { definitionMembers, includeText, memberText, type ChangeRequest, type IndexDefinition, type IndexDetail, type IndexSaveResult } from './model';
import '../../styles/history.css';
import '../../styles/index_manage.css';

const STATUS: Record<ChangeRequest['status'], { label: string; variant: 'attention' | 'success' | 'danger' }> = {
  REQUESTED: { label: t('승인 대기'), variant: 'attention' },
  APPROVED: { label: t('승인'), variant: 'success' },
  REJECTED: { label: t('반려'), variant: 'danger' },
};

const sourceLabel = (source: string) => (source === 'COLUMN' ? t('컬럼 속성') : t('직접 등록'));
const kindLabel = (unique: boolean | string) => (unique === true || unique === 'Y' ? 'UNIQUE' : 'INDEX');

/**
 * 인덱스 관리 (M-14) : 프로젝트의 물리 모델 인덱스(TB_TBL_IDX) 목록·등록·수정·삭제·승인, 변경 요청 승인·반려, DDL 미리보기.
 * 물리 ERD 의 [인덱스·제약조건] 과 같은 데이터다. 컬럼 속성(인덱스 여부)으로 만든 인덱스는 컬럼 관리에서 바꾼다.
 */
export function IndexManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const navigate = useNavigate();
  const { canView } = useAuth();
  const canEdit = canView('physical-model-edit');
  const { schemas } = useSchemas();
  const [searchParams, setSearchParams] = useSearchParams();
  const tableId = /^\d+$/.test(searchParams.get('tableId') ?? '') ? searchParams.get('tableId')! : '';
  const [schemaId, setSchemaId] = useState('');
  const [isApproval, setIsApproval] = useState('');
  const [indexSource, setIndexSource] = useState('');
  const params = Object.fromEntries(Object.entries({ schemaId, tableId, isApproval, indexSource }).filter(([, v]) => v));
  const reload = () => grid.current?.reload();

  const openForm = () => {
    dialog.open({
      title: t('인덱스 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <IndexForm initialTableId={tableId ? Number(tableId) : undefined} onSaved={() => { close(); reload(); }} onCancel={close} />,
    });
  };

  const openDetail = (row: Row) => {
    dialog.open({
      title: t('인덱스 상세 · {0}', row.indexName),
      className: 'dialog-wide',
      content: ({ close, setTitle }: DialogControls) => (
        <IndexDetailView indexId={row.id} onChanged={reload} setTitle={setTitle}
          onNavigate={(path) => { close(); navigate(path); }} onClose={close} />
      ),
    });
  };

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('인덱스 관리')}
        description={t('프로젝트의 인덱스를 조회하고 등록·수정·삭제·승인합니다. 물리 ERD 의 인덱스와 같은 데이터이고, 컬럼의 인덱스 여부로 만든 인덱스(출처 : 컬럼 속성)는 컬럼 관리에서 바꿉니다. 승인된 인덱스를 고치면 변경 요청이 되어 승인 후 반영됩니다.')} />
      <div id="gridContainer" className="index-grid-container">
        <Grid
          ref={grid}
          id="indexManageGrid"
          columns={indexColumns}
          minWidth={INDEX_GRID_MIN_WIDTH}
          dataUrl="/api/physical/index"
          params={params}
          searchFilters={
            <>
              {tableId && (
                <Label variant="accent">{t('테이블 #{0}', tableId)} <button type="button" className="logical-link" aria-label={t('테이블 조건 해제')} onClick={() => setSearchParams({}, { replace: true })}>×</button></Label>
              )}
              <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
                <option value="">{t('전체 스키마')}</option>
                {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
              </select>
              <select className="search-column-select" aria-label={t('승인 상태')} value={isApproval} onChange={(e) => setIsApproval(e.target.value)}>
                <option value="">{t('전체 승인 상태')}</option>
                <option value="Y">{t('승인')}</option>
                <option value="N">{t('승인 대기')}</option>
              </select>
              <select className="search-column-select" aria-label={t('출처')} value={indexSource} onChange={(e) => setIndexSource(e.target.value)}>
                <option value="">{t('전체 출처')}</option>
                <option value="USER">{t('직접 등록')}</option>
                <option value="COLUMN">{t('컬럼 속성')}</option>
              </select>
            </>
          }
          crudActions={canEdit ? 'C' : ''}
          onAction={{ add: openForm }}
          onRowClick={openDetail}
          emptyText={t('등록된 인덱스가 없습니다.')}
          renderCell={(field, row) => {
            // 긴 이름·컬럼 목록은 한 줄로 자르고 전체 값은 툴팁(title)·상세에서 본다
            if (field === 'tableName' || field === 'schemaName' || field === 'columnNames' || field === 'condition') return <span className="index-cell" title={row[field] ?? undefined}><Mono>{row[field]}</Mono></span>;
            if (field === 'indexName') return <span className="index-name-cell"><span className="index-cell" title={row.indexName}><Mono>{row.indexName}</Mono></span>{row.pendingRequest === 'Y' && <Label variant="attention">{t('변경 요청')}</Label>}</span>;
            if (field === 'isUnique') return kindLabel(row.isUnique);
            if (field === 'indexSource') return sourceLabel(row.indexSource);
            if (field === 'isApproval') return <Approval value={row.isApproval} />;
            return undefined;
          }}
        />
      </div>
    </>
  );
}

/** 인덱스 상세 : 테이블 상세와 같은 구성 (상태 → 기본 정보 → 키 구성 → 고급 속성 → 변경 정보, 아래 버튼). 이름이 바뀌면 제목도 바꾼다 */
function IndexDetailView({ indexId, onChanged, onNavigate, onClose, setTitle }: {
  indexId: number;
  onChanged: () => void;
  onNavigate: (path: string) => void;
  onClose: () => void;
  setTitle: DialogControls['setTitle'];
}) {
  const dialog = useDialog();
  const { approvalAvailable, canView } = useAuth();
  const { openTableDetailById } = useTableDetail();
  const canEdit = canView('physical-model-edit');
  const detail = useQuery({
    queryKey: ['index-detail', indexId],
    queryFn: () => api<IndexDetail>(`/api/physical/index/${enc(indexId)}`, { silent: true }),
    gcTime: 0,
  });
  const indexName = detail.data?.index.indexName;
  useEffect(() => {
    if (indexName) setTitle(t('인덱스 상세 · {0}', indexName));
  }, [indexName, setTitle]);
  const changed = () => {
    void detail.refetch();
    onChanged();
  };

  if (detail.isError) return <p role="alert">{t('인덱스를 불러오지 못했습니다.')} {detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const { index, requests } = detail.data;
  const approved = !!index.isApproval;
  const fromColumn = index.indexSource === 'COLUMN';
  const pending = requests.some((r) => r.status === 'REQUESTED');
  const members = definitionMembers(index);
  const replaced = detail.data.replacedColumnIndexes ?? [];

  const edit = () => {
    dialog.popup({
      title: t('인덱스 수정 · {0}', index.indexName),
      size: 'wide',
      content: ({ close }: DialogControls) => (
        <IndexForm detail={detail.data} onCancel={close} onSaved={(result: IndexSaveResult | null) => {
          close();
          changed();
          if (result && !result.applied) dialog.alert(t('변경 요청을 만들었습니다. 승인되면 반영됩니다.'));
        }} />
      ),
    });
  };
  const remove = () => {
    dialog.confirm(t('{0} 인덱스를 삭제하시겠습니까?', index.indexName), async () => {
      await api(`/api/physical/index/${enc(index.id)}`, { method: 'DELETE' });
      onChanged();
      onClose();
    });
  };
  const toggleApproval = () => {
    const message = approved
      ? (replaced.length === 0 && index.isUnique ? t('인덱스 승인을 취소하시겠습니까? 이 UNIQUE 가 대신하던 컬럼 인덱스가 있으면 다시 만들어집니다.') : t('인덱스 승인을 취소하시겠습니까?'))
      : replaced.length ? t('인덱스를 승인하시겠습니까? 승인하면 이 UNIQUE 인덱스가 같은 컬럼의 컬럼 인덱스 {0} 을(를) 대신하며 그 인덱스는 삭제됩니다.', replaced.join(', '))
      : t('인덱스를 승인하시겠습니까?');
    dialog.confirm(message, async () => {
      await api(`/api/physical/index/${enc(index.id)}/approval/${!approved}`, { method: 'PATCH' });
      changed();
    });
  };
  const process = (request: ChangeRequest, approve: boolean) => {
    dialog.confirm(approve ? t('변경 요청을 승인하고 반영하시겠습니까?') : t('변경 요청을 반려하시겠습니까?'), async () => {
      await api(`/api/physical/index/request/${enc(request.id)}/${approve}`, { method: 'PATCH' });
      changed();
    });
  };
  const ddl = () => dialog.popup({ title: t('DDL · {0}', index.indexName), size: 'large', content: <TableDdlView ddlUrl={`/api/physical/index/${enc(index.id)}/ddl`} /> });

  const basic: [string, string | null | undefined, boolean?][] = [
    [t('테이블'), `${detail.data.schemaName ? `${detail.data.schemaName}.` : ''}${detail.data.tableName}`, true],
    [t('인덱스명'), index.indexName, true],
    [tc('인덱스', '구분'), kindLabel(index.isUnique)],
    [t('인덱스 유형'), index.indexType || t('기본 (DBMS 기본 방식)')],
    [t('출처'), sourceLabel(index.indexSource)],
  ];
  const advanced: [string, string][] = [
    ['INCLUDE', includeText(index)],
    [t('WHERE 조건'), index.condition ?? ''],
  ];

  return (
    <div className="index-detail">
      <div className="dict-detail-status">
        <Label variant={approved ? 'success' : 'attention'} dot>{approved ? t('승인') : t('승인 대기')}</Label>
        {pending && <Label variant="attention">{t('변경 요청')}</Label>}
        <span className="gh-muted">{fromColumn
          ? t('컬럼의 인덱스 여부로 만든 인덱스입니다. 이름·승인 상태는 컬럼을 따르고, 컬럼 관리에서 인덱스 여부를 바꿉니다.')
          : approved ? t('승인된 인덱스를 고치면 변경 요청이 되어 승인 후 반영됩니다.') : ''}</span>
      </div>
      {replaced.length > 0 && (
        <p className="form-help index-detail-note" role="note">{approved
          ? t('같은 컬럼의 컬럼 인덱스 {0} 이(가) 함께 남아 있습니다 (예전부터 공존하던 데이터, 정리 대상). 자동으로 지우지 않습니다.', replaced.join(', '))
          : t('승인하면 이 UNIQUE 인덱스가 같은 컬럼의 컬럼 인덱스 {0} 을(를) 대신합니다. 그때까지는 그 인덱스가 그대로 유지됩니다.', replaced.join(', '))}</p>
      )}

      <div className="dict-detail-columns">
        <h4>{t('기본 정보')}</h4>
        <DetailList items={basic} />
      </div>

      <div className="dict-detail-columns">
        <h4>{t('검색 키 ({0})', members.length)}</h4>
        <div className="table-wrap">
          <table>
            <thead><tr>{['#', t('컬럼 / 표현식'), t('정렬'), 'NULLS'].map((h) => <th key={h}>{h}</th>)}</tr></thead>
            <tbody>
              {members.map((m, i) => (
                <tr key={i}>
                  <td className="text-center">{i + 1}</td>
                  <td className="gh-mono">{m.expression != null ? `(${m.expression})` : (m.columnName ?? `#${m.columnId}`)}</td>
                  <td>{m.direction === 'DESC' ? 'DESC' : 'ASC'}</td>
                  <td>{m.nulls ?? t('기본')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="dict-detail-columns">
        <h4>{t('고급 속성')}</h4>
        <DetailList items={advanced.map(([label, value]) => [label, value, true])} />
      </div>

      <div className="dict-detail-columns">
        <h4>{t('변경 정보')}</h4>
        <DetailList items={[
          [t('등록자'), [detail.data.createdBy, formatDateTime(detail.data.created)].filter(Boolean).join(' · ')],
          [t('수정자'), [detail.data.updatedBy, formatDateTime(detail.data.updated)].filter(Boolean).join(' · ')],
        ]} />
      </div>

      <div className="dict-detail-columns">
        <h4>{t('변경 요청 ({0})', requests.length)}</h4>
        {requests.length === 0 ? <p className="gh-muted">{t('변경 요청이 없습니다.')}</p> : requests.map((r) => (
          <section key={r.id} className="index-request" aria-label={t('변경 요청 #{0}', r.id)}>
            <div className="index-request-head">
              <Label variant={STATUS[r.status].variant} dot>{STATUS[r.status].label}</Label>
              <span className="gh-muted">{t('요청')} <User name={r.requestedBy} /> <DateTime value={r.requestedAt} /></span>
              {r.processedBy && <span className="gh-muted">{t('처리')} <User name={r.processedBy} /> <DateTime value={r.processedAt} /></span>}
              {approvalAvailable && r.status === 'REQUESTED' && (
                <span className="index-request-actions">
                  <button type="button" className="btn btn-sm btn-primary" onClick={() => process(r, true)}>{tc('버튼', '승인')}</button>
                  <button type="button" className="btn btn-sm" onClick={() => process(r, false)}>{tc('버튼', '반려')}</button>
                </span>
              )}
            </div>
            <DefinitionDiff before={r.before} after={r.after} />
          </section>
        ))}
      </div>

      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={ddl}>{t('DDL 보기')}</button>
        {canView('physical-erd') && <button type="button" className="btn" onClick={() => onNavigate(`/physicalErd?schemaId=${enc(detail.data.schemaId ?? '')}&tableId=${enc(index.tableId)}`)}>{t('ERD 에서 보기')}</button>}
        {canView('table-manage') && <button type="button" className="btn" onClick={() => void openTableDetailById(index.tableId, { onChanged, onNavigate })}>{t('테이블 상세')}</button>}
        {approvalAvailable && !fromColumn && (
          <button type="button" className={approved ? 'btn btn-danger' : 'btn btn-primary'} disabled={approved && pending} onClick={toggleApproval}>
            {approved ? t('승인취소') : tc('버튼', '승인')}
          </button>
        )}
        {canEdit && !fromColumn && <button type="button" className="btn btn-danger" disabled={approved} title={approved ? t('승인된 인덱스는 삭제할 수 없습니다. 승인을 취소한 뒤 삭제합니다.') : undefined} onClick={remove}>{t('삭제')}</button>}
        {canEdit && !fromColumn && <button type="button" className="btn" disabled={pending} title={pending ? t('승인 대기 중인 변경 요청이 있습니다.') : undefined} onClick={edit}>{approved && !approvalAvailable ? t('변경 요청') : t('수정')}</button>}
      </div>
    </div>
  );
}

/** 항목명 : 값 (테이블 상세와 같은 dict-detail, 빈 값은 -) */
function DetailList({ items }: { items: [string, string | null | undefined, boolean?][] }) {
  return (
    <dl className="dict-detail">
      {items.flatMap(([label, value, mono]) => {
        const empty = !value;
        return [
          <dt key={`t-${label}`}>{label}</dt>,
          <dd key={`d-${label}`} className={empty ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{empty ? '-' : value}</dd>,
        ];
      })}
    </dl>
  );
}

/** 변경 요청의 전후 비교 : 모델 변경 이력과 같은 표 (항목 / 변경 전 / 변경 후, 바뀐 항목은 취소선·강조) */
function DefinitionDiff({ before, after }: { before: IndexDefinition; after: IndexDefinition }) {
  const rows: [string, (d: IndexDefinition) => string][] = [
    [t('인덱스명'), (d) => d.indexName],
    [tc('인덱스', '구분'), (d) => kindLabel(d.isUnique)],
    [t('인덱스 유형'), (d) => d.indexType || '-'],
    [t('검색 키 (순서대로)'), (d) => definitionMembers(d).map((m) => memberText(m)).join('\n')],
    ['INCLUDE', (d) => includeText(d) || '-'],
    [t('WHERE 조건'), (d) => d.condition || '-'],
  ];
  return (
    <div className="table-wrap index-diff-wrap">
      <table className="history-diff">
        <thead><tr><th>{t('항목')}</th><th>{t('변경 전')}</th><th>{t('변경 후')}</th></tr></thead>
        <tbody>
          {rows.map(([label, value]) => {
            const changed = value(before) !== value(after);
            return (
              <tr key={label} className={changed ? 'is-changed' : undefined}>
                <th scope="row">{label}</th>
                <td className={`gh-mono${changed ? ' history-before' : ''}`}>{value(before)}</td>
                <td className={`gh-mono${changed ? ' history-after' : ''}`}>{value(after)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
