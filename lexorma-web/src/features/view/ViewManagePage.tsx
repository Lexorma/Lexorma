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
import { VIEW_GRID_MIN_WIDTH, viewColumns } from './columns';
import { ViewForm } from './ViewForm';
import { isInternal, normalizedSql, referenceName, referenceTypeLabel, type ViewChangeRequest, type ViewDefinition, type ViewDetail, type ViewSaveResult } from './model';
import '../../styles/history.css';
import '../../styles/index_manage.css';
import '../../styles/view_manage.css';

const STATUS: Record<ViewChangeRequest['status'], { label: string; variant: 'attention' | 'success' | 'danger' }> = {
  REQUESTED: { label: t('승인 대기'), variant: 'attention' },
  APPROVED: { label: t('승인'), variant: 'success' },
  REJECTED: { label: t('반려'), variant: 'danger' },
};

/**
 * VIEW 관리 (M-15) : 물리 모델의 VIEW(TB_VIEW) 목록·등록·수정·삭제·승인, 변경 요청 승인·반려, DDL 미리보기·내보내기.
 * 인덱스 관리(M-14)와 같은 화면 구성이다. 정의 SQL 은 어디에서도 실행하지 않는다. ?viewId= 로 들어오면 그 VIEW 의 상세를 연다 (물리 ERD 에서 이동).
 */
export function ViewManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const navigate = useNavigate();
  const { canView } = useAuth();
  const canEdit = canView('physical-model-edit');
  const { schemas } = useSchemas();
  const [searchParams, setSearchParams] = useSearchParams();
  const [schemaId, setSchemaId] = useState('');
  const [isApproval, setIsApproval] = useState('');
  const params = Object.fromEntries(Object.entries({ schemaId, isApproval }).filter(([, v]) => v));
  const reload = () => grid.current?.reload();

  const openForm = () => {
    dialog.open({
      title: t('VIEW 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ViewForm onSaved={() => { close(); reload(); }} onCancel={close} />,
    });
  };

  const openDetail = (viewId: number, viewName?: string) => {
    dialog.open({
      title: t('VIEW 상세 · {0}', viewName ?? `#${viewId}`),
      className: 'dialog-wide',
      content: ({ close, setTitle }: DialogControls) => (
        <ViewDetailView viewId={viewId} onChanged={reload} setTitle={setTitle} onOpenView={openDetail}
          onNavigate={(path) => { close(); navigate(path); }} onClose={close} />
      ),
    });
  };

  // 물리 ERD 에서 넘어온 경우 (?viewId=) : 상세를 한 번 열고 파라미터는 지운다
  const linked = useRef(searchParams.get('viewId'));
  useEffect(() => {
    if (!linked.current || !/^\d+$/.test(linked.current)) return;
    openDetail(Number(linked.current));
    linked.current = null;
    setSearchParams({}, { replace: true });
  }, []);

  const exportAll = () => dialog.popup({
    title: t('VIEW DDL 내보내기{0}', schemaId ? ` · ${schemas.find((s) => String(s.id) === schemaId)?.schemaName ?? ''}` : ''),
    size: 'large',
    content: <TableDdlView ddlUrl={`/api/physical/view/ddl${schemaId ? `?schemaId=${enc(schemaId)}` : ''}`} downloadName="views.sql" />,
  });

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('VIEW 관리')}
        description={t('프로젝트의 VIEW 를 테이블과 따로 관리합니다. 정의 SQL(조회 문)·출력 컬럼·참조 객체를 등록하고 CREATE VIEW DDL 을 만듭니다. 정의 SQL 은 실행하지 않으며, 승인된 VIEW 를 고치면 변경 요청이 되어 승인 후 한 번에 반영됩니다.')} />
      <div id="gridContainer" className="index-grid-container">
        <Grid
          ref={grid}
          id="viewManageGrid"
          columns={viewColumns}
          minWidth={VIEW_GRID_MIN_WIDTH}
          dataUrl="/api/physical/view"
          params={params}
          searchFilters={
            <>
              <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
                <option value="">{t('전체 스키마')}</option>
                {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
              </select>
              <select className="search-column-select" aria-label={t('승인 상태')} value={isApproval} onChange={(e) => setIsApproval(e.target.value)}>
                <option value="">{t('전체 승인 상태')}</option>
                <option value="Y">{t('승인')}</option>
                <option value="N">{t('승인 대기')}</option>
              </select>
            </>
          }
          extraActions={<button type="button" className="btn" onClick={exportAll}>{t('DDL 내보내기')}</button>}
          crudActions={canEdit ? 'C' : ''}
          onAction={{ add: openForm }}
          onRowClick={(row: Row) => openDetail(row.id, row.viewName)}
          emptyText={t('등록된 VIEW 가 없습니다.')}
          renderCell={(field, row) => {
            // 긴 이름·설명은 한 줄로 자르고 전체 값은 툴팁(title)·상세에서 본다
            if (field === 'schemaName' || field === 'viewKorName' || field === 'viewDescription') return <span className="view-cell" title={row[field] ?? undefined}>{field === 'schemaName' ? <Mono>{row[field]}</Mono> : row[field]}</span>;
            if (field === 'viewName') return <span className="view-name-cell"><span className="view-cell" title={row.viewName}><Mono>{row.viewName}</Mono></span>{row.pendingRequest === 'Y' && <Label variant="attention">{t('변경 요청')}</Label>}</span>;
            if (field === 'isApproval') return <Approval value={row.isApproval} />;
            if (field === 'updated') return <span className="view-cell"><DateTime value={row.updated} /></span>;
            return undefined;
          }}
        />
      </div>
    </>
  );
}

/** VIEW 상세 : 인덱스·테이블 상세와 같은 구성 (상태 → 기본 정보 → 정의 SQL → 출력 컬럼 → 참조 객체 → 변경 정보 → 변경 요청, 아래 버튼) */
function ViewDetailView({ viewId, onChanged, onNavigate, onClose, onOpenView, setTitle }: {
  viewId: number;
  onChanged: () => void;
  onNavigate: (path: string) => void;
  onClose: () => void;
  onOpenView: (viewId: number, viewName?: string) => void;
  setTitle: DialogControls['setTitle'];
}) {
  const dialog = useDialog();
  const { approvalAvailable, canView } = useAuth();
  const { openTableDetailById } = useTableDetail();
  const canEdit = canView('physical-model-edit');
  const detail = useQuery({
    queryKey: ['view-detail', viewId],
    queryFn: () => api<ViewDetail>(`/api/physical/view/${enc(viewId)}`, { silent: true }),
    gcTime: 0,
  });
  const viewName = detail.data?.view.viewName;
  useEffect(() => {
    if (viewName) setTitle(t('VIEW 상세 · {0}', viewName));
  }, [viewName, setTitle]);
  const changed = () => {
    void detail.refetch();
    onChanged();
  };

  if (detail.isError) return <p role="alert">{t('VIEW 를 불러오지 못했습니다.')} {detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;
  const { view, requests, referencedBy } = detail.data;
  const approved = !!detail.data.isApproval;
  const pending = requests.some((r) => r.status === 'REQUESTED');

  const edit = () => {
    dialog.popup({
      title: t('VIEW 수정 · {0}', view.viewName),
      size: 'wide',
      content: ({ close }: DialogControls) => (
        <ViewForm detail={detail.data} onCancel={close} onSaved={(result: ViewSaveResult) => {
          close();
          changed();
          if (!result.applied) dialog.alert(t('변경 요청을 만들었습니다. 승인되면 반영됩니다.'));
        }} />
      ),
    });
  };
  const remove = () => {
    dialog.confirm(t('{0} VIEW 를 삭제하시겠습니까? 출력 컬럼·참조 객체·변경 요청 이력도 함께 삭제됩니다.', view.viewName), async () => {
      await api(`/api/physical/view/${enc(viewId)}`, { method: 'DELETE' });
      onChanged();
      onClose();
    });
  };
  const toggleApproval = () => {
    dialog.confirm(approved ? t('VIEW 승인을 취소하시겠습니까?') : t('VIEW 를 승인하시겠습니까?'), async () => {
      await api(`/api/physical/view/${enc(viewId)}/approval/${!approved}`, { method: 'PATCH' });
      changed();
    });
  };
  const process = (request: ViewChangeRequest, approve: boolean) => {
    dialog.confirm(approve ? t('변경 요청을 승인하고 반영하시겠습니까? 기본 정보·정의 SQL·출력 컬럼·참조 객체가 한 번에 반영됩니다.') : t('변경 요청을 반려하시겠습니까?'), async () => {
      await api(`/api/physical/view/request/${enc(request.id)}/${approve}`, { method: 'PATCH' });
      changed();
    });
  };
  const ddl = () => dialog.popup({ title: t('DDL · {0}', view.viewName), size: 'large', content: <TableDdlView ddlUrl={`/api/physical/view/${enc(viewId)}/ddl`} downloadName={`${view.viewName}.sql`} /> });
  const copySql = async () => {
    try {
      await navigator.clipboard.writeText(view.definition);
      dialog.alert(t('정의 SQL 을 복사했습니다.'));
    } catch {
      dialog.alert(t('복사하지 못했습니다. 정의 SQL 을 직접 선택해 복사해주세요.'));
    }
  };

  return (
    <div className="index-detail view-detail">
      <div className="dict-detail-status">
        <Label variant={approved ? 'success' : 'attention'} dot>{approved ? t('승인') : t('승인 대기')}</Label>
        {pending && <Label variant="attention">{t('변경 요청')}</Label>}
        <span className="gh-muted">{approved ? t('승인된 VIEW 를 고치면 변경 요청이 되어 승인 후 반영됩니다.') : ''}</span>
      </div>

      <div className="dict-detail-columns">
        <h4>{t('기본 정보')}</h4>
        <DetailList items={[
          [t('VIEW명'), view.viewName, true],
          [t('스키마'), view.schemaName, true],
          [t('논리명'), view.viewKorName],
          [t('설명'), view.viewDescription],
        ]} />
      </div>

      <div className="dict-detail-columns">
        <h4>{t('정의 SQL')} <button type="button" className="btn btn-sm view-copy" onClick={() => void copySql()}>{t('복사')}</button></h4>
        <pre className="table-ddl-code view-sql"><code>{view.definition}</code></pre>
        <p className="form-help">{t('VIEW 정의 SQL 은 실행하지 않습니다. 실제 DBMS 적용 시 문법 및 참조 객체 검증이 필요합니다.')}</p>
      </div>

      <div className="dict-detail-columns">
        <h4>{t('출력 컬럼 ({0})', view.columns.length)} <span className="gh-muted">{t('직접 관리하는 정보')}</span></h4>
        {view.columns.length === 0 ? <p className="gh-muted">{t('출력 컬럼이 없습니다.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr>{['#', t('컬럼명'), t('논리명'), t('데이터 타입'), t('설명')].map((h) => <th key={h}>{h}</th>)}</tr></thead>
              <tbody>
                {view.columns.map((c, i) => (
                  <tr key={i}>
                    <td className="text-center">{i + 1}</td>
                    <td className="gh-mono">{c.columnName}</td>
                    <td>{c.columnKorName ?? ''}</td>
                    <td className="gh-mono">{c.dataType ?? <span className="gh-muted">-</span>}</td>
                    <td>{c.columnDescription ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="dict-detail-columns">
        <h4>{t('참조 객체 ({0})', view.references.length)} <span className="gh-muted">{t('직접 관리하는 정보 · SQL 에서 확인한 관계가 아닙니다')}</span></h4>
        {view.references.length === 0 ? <p className="gh-muted">{t('참조 객체가 없습니다.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr>{[t('유형'), t('스키마'), t('객체명'), t('연결'), ''].map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
              <tbody>
                {view.references.map((r, i) => (
                  <tr key={i}>
                    <td>{referenceTypeLabel(r.referenceType)}</td>
                    <td className="gh-mono">{r.schemaName ?? '-'}</td>
                    <td className="gh-mono">{r.objectName}</td>
                    <td>{isInternal(r) ? <Label variant="success">{t('모델 객체')}</Label> : <Label variant="attention">{t('미확인')}</Label>}</td>
                    <td>
                      {r.tableId != null && canView('table-manage') && <button type="button" className="btn btn-sm" onClick={() => void openTableDetailById(r.tableId!, { onChanged, onNavigate })}>{t('테이블 상세')}</button>}
                      {r.viewId != null && <button type="button" className="btn btn-sm" onClick={() => onOpenView(r.viewId!, r.objectName ?? undefined)}>{t('VIEW 상세')}</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {referencedBy.length > 0 && <p className="form-help" role="note">{t('이 VIEW 를 참조하는 VIEW : {0} (참조가 있으면 삭제할 수 없습니다)', referencedBy.join(', '))}</p>}
      </div>

      <div className="dict-detail-columns">
        <h4>{t('변경 정보')}</h4>
        <DetailList items={[
          [t('등록자'), [detail.data.createdBy, formatDateTime(detail.data.created)].filter(Boolean).join(' · ')],
          [t('수정자'), [detail.data.updatedBy, formatDateTime(detail.data.updated)].filter(Boolean).join(' · ')],
        ]} />
        {canView('model-history') && (
          <button type="button" className="btn btn-sm" onClick={() => onNavigate(`/modelHistory?targetType=PHYSICAL_VIEW&targetId=${enc(viewId)}`)}>{t('변경 이력 보기')}</button>
        )}
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
        {canView('physical-erd') && <button type="button" className="btn" onClick={() => onNavigate(`/physicalErd?schemaId=${enc(view.schemaId ?? '')}&viewId=${enc(viewId)}`)}>{t('ERD 에서 보기')}</button>}
        {approvalAvailable && (
          <button type="button" className={approved ? 'btn btn-danger' : 'btn btn-primary'} disabled={approved && pending}
            title={approved && pending ? t('승인 대기 중인 변경 요청이 있습니다.') : undefined} onClick={toggleApproval}>
            {approved ? t('승인취소') : tc('버튼', '승인')}
          </button>
        )}
        {canEdit && <button type="button" className="btn btn-danger" disabled={approved || referencedBy.length > 0}
          title={approved ? t('승인된 VIEW 는 삭제할 수 없습니다. 승인을 취소한 뒤 삭제합니다.') : referencedBy.length ? t('다른 VIEW 가 참조하고 있어 삭제할 수 없습니다.') : undefined} onClick={remove}>{t('삭제')}</button>}
        {canEdit && <button type="button" className="btn" disabled={pending} title={pending ? t('승인 대기 중인 변경 요청이 있습니다.') : undefined} onClick={edit}>{approved && !approvalAvailable ? t('변경 요청') : t('수정')}</button>}
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

/** 변경 요청의 전후 비교 : 모델 변경 이력과 같은 표. 참조 객체는 순서를 보지 않는다. 정의 SQL 이 공백·줄바꿈만 다르면 그렇게 알린다 */
function DefinitionDiff({ before, after }: { before: ViewDefinition; after: ViewDefinition }) {
  const columns = (d: ViewDefinition) => d.columns.map((c, i) => `${i + 1}. ${c.columnName}${c.columnKorName ? ` (${c.columnKorName})` : ''}${c.dataType ? ` : ${c.dataType}` : ''}${c.columnDescription ? ` - ${c.columnDescription}` : ''}`).join('\n') || '-';
  const references = (d: ViewDefinition) => d.references.map((r) => `[${referenceTypeLabel(r.referenceType)}] ${referenceName(r)}${isInternal(r) ? '' : ` (${t('미확인')})`}`).sort().join('\n') || '-';
  const rows: [string, (d: ViewDefinition) => string][] = [
    [t('VIEW명'), (d) => d.viewName],
    [t('스키마'), (d) => d.schemaName ?? '-'],
    [t('논리명'), (d) => d.viewKorName || '-'],
    [t('설명'), (d) => d.viewDescription || '-'],
    [t('정의 SQL'), (d) => d.definition],
    [t('출력 컬럼 (순서대로)'), columns],
    [t('참조 객체'), references],
  ];
  const whitespaceOnly = before.definition !== after.definition && normalizedSql(before.definition) === normalizedSql(after.definition);
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
                <td className={`gh-mono view-diff-value${changed ? ' history-before' : ''}`}>{value(before)}</td>
                <td className={`gh-mono view-diff-value${changed ? ' history-after' : ''}`}>{value(after)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {whitespaceOnly && <p className="form-help" role="note">{t('정의 SQL 은 공백·줄바꿈만 다릅니다 (원문은 그대로 저장됩니다).')}</p>}
    </div>
  );
}
