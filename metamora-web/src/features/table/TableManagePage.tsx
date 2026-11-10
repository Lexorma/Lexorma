import { useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Mono, YN } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { DownloadIcon, UploadIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, ApiError, download, enc } from '../../lib/api';
import { isBlank } from '../../lib/format';
import { useColumnBulkEditor } from '../column/ColumnBulkEditor';
import { TableDdlView } from './TableDdl';
import { TableForm } from './TableForm';
import { tableColumns } from './columns';

/** 테이블 관리 : 테이블 설계 정보 등록·승인, 상세(컬럼 목록 / 컬럼 일괄 편집 / DDL) */
export function TableManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const openColumnBulkEditor = useColumnBulkEditor();
  const reload = () => grid.current?.reload();

  const openForm = (type: 'C' | 'U', row?: Row) => {
    dialog.open({
      title: type === 'C' ? '테이블 등록' : '테이블 수정',
      content: ({ close }: DialogControls) => <TableForm type={type} row={row} onSaved={() => { close(); reload(); }} />,
    });
  };

  const remove = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    if (checked.some((e) => e.isApproval === 'Y')) {
      dialog.alert('체크된 데이터에 승인된 정보도 포함되어있습니다. 승인이 완료된 경우, 삭제가 불가능합니다.');
      return;
    }
    dialog.confirm('체크된 데이터를 삭제하시겠습니까?', async () => {
      const response = await api<{ result: boolean }>('/api/deleteTable', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert('정상적으로 삭제되었습니다.', reload);
    });
  };

  const updateActive = (isApproval: boolean) => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    const msg = isApproval ? '승인' : '승인취소';
    dialog.confirm(`체크된 테이블 정보를 ${msg}하시겠습니까?`, async () => {
      if (!checked.some((e) => e.isApproval === (isApproval ? 'N' : 'Y'))) {
        dialog.alert(`${msg} 필요한 정보가 선택되지 않았습니다.`);
        return;
      }
      const response = await api<{ result: boolean }>(`/api/saveTable/${isApproval}`, { method: 'PATCH', body: checked });
      if (response.result) dialog.alert(`정상적으로 ${msg}처리 되었습니다.`, reload);
    });
  };

  const onFile = () => {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    const clear = () => {
      if (fileRef.current) fileRef.current.value = '';
    };
    dialog.confirm('업로드 하시겠습니까?', async () => {
      const formData = new FormData();
      formData.append('file', file);
      try {
        const response = await api<{ result: string }>('/api/uploadTableExcelFile', { method: 'POST', body: formData });
        if (response.result === 'success') {
          dialog.alert('업로드가 완료되었습니다.');
          reload();
        }
      } finally {
        clear();
      }
    }, clear);
  };

  // 행 선택 : 테이블 상세 (그리드에서 숨긴 항목 + 등록된 컬럼 목록)
  const openDetail = (row: Row) => {
    const approved = row.isApproval === 'Y';
    dialog.open({
      title: `테이블 상세 · ${row.tableName ?? ''}`,
      // 컬럼 목록 표가 들어가므로 넓게 연다
      className: 'dialog-table-detail',
      content: ({ close }: DialogControls) => (
        <TableDetail
          row={row}
          onEditColumns={() => {
            close();
            openColumnBulkEditor(row, {
              onSaved: () => {
                reload();
                openDetail(row); // 저장한 컬럼으로 상세를 다시 연다
              },
            });
          }}
          onDdl={() => dialog.popup({ title: `DDL · ${row.tableName ?? ''}`, size: 'large', content: <TableDdlView tableId={row.id} /> })}
          onEdit={approved ? undefined : () => { close(); openForm('U', row); }}
        />
      ),
    });
  };

  return (
    <>
      <PageHeader category="데이터 관리" title="테이블 관리"
        description="테이블 설계 정보를 등록하고 승인합니다. 테이블명은 한글명(예: 사용_내역_관리)을 승인된 표준 단어의 영문약어로 바꿔 만듭니다. 행을 누르면 테이블 정보를 수정할 수 있습니다." />
      <input type="file" ref={fileRef} style={{ display: 'none' }} accept=".xlsx, .xls" onChange={onFile} />

      <div id="gridContainer">
        <Grid
          ref={grid}
          id="tableManageGrid"
          columns={tableColumns}
          dataUrl="/api/selectTable"
          crudActions="anCD"
          extraActions={
            <>
              <button type="button" className="btn" id="download-template" onClick={() => download('/api/downloadTemplate/tableTemplate', '테이블 템플릿.xlsx')}>
                <DownloadIcon />템플릿
              </button>
              <button type="button" id="excel-upload" className="btn" onClick={() => fileRef.current?.click()}><UploadIcon />업로드</button>
            </>
          }
          onAction={{ add: () => openForm('C'), delete: remove, active: () => updateActive(true), unactive: () => updateActive(false) }}
          onRowClick={openDetail}
          renderCell={(field, row) => {
            if (field === 'tableName') return <Mono>{row.tableName}</Mono>;
            if (field === 'isMasterTable') return <YN value={row.isMasterTable} text="마스터" />;
            if (field === 'isApproval') return <Approval value={row.isApproval} />;
            return undefined;
          }}
        />
      </div>
    </>
  );
}

interface ColumnInfo {
  id: number;
  columnName: string;
  columnDesc?: string;
  dataType?: string;
  maxLength?: number;
  isPk?: string;
  isNullable?: string;
  isApproval?: string;
  sortOrder?: number;
}

function ynLabel(value: unknown, text: string, variant = 'accent') {
  return value === 'Y' ? <span className={`gh-label gh-label-${variant}`}>{text}</span> : <span className="gh-muted">-</span>;
}

function TableDetail({ row, onEditColumns, onDdl, onEdit }: { row: Row; onEditColumns: () => void; onDdl: () => void; onEdit?: () => void }) {
  const approved = row.isApproval === 'Y';
  const { data, error } = useQuery({
    queryKey: ['table-columns', row.id],
    queryFn: () => api<{ columnInfos?: ColumnInfo[] }>(`/api/selectColumn/tableColumn/${enc(row.id)}`, { silent: true }),
    gcTime: 0,
  });

  const order = (v?: number | null) => v ?? Number.MAX_SAFE_INTEGER;
  const columns = (data?.columnInfos ?? []).slice().sort((a, b) => order(a.sortOrder) - order(b.sortOrder));

  const items: [string, unknown, boolean?][] = [
    ['테이블명', row.tableName, true],
    ['테이블 한글명', row.tableKorName],
    ['테이블 설명', row.tableDescription],
    ['마스터 테이블', row.isMasterTable === 'Y' ? 'Y' : 'N'],
    ['정렬 순서', row.sortOrder],
  ];

  return (
    <div>
      <div className="dict-detail-status">
        <span className={`gh-label gh-label-dot ${approved ? 'gh-label-success' : 'gh-label-attention'}`}>{approved ? '승인' : '승인 대기'}</span>
        <span className="gh-muted">{approved ? '승인된 테이블은 수정할 수 없습니다.' : ''}</span>
      </div>
      <dl className="dict-detail">
        {items.map(([label, value, mono]) => {
          const empty = isBlank(value);
          return [
            <dt key={`t-${label}`}>{label}</dt>,
            <dd key={`d-${label}`} className={[empty ? 'gh-muted' : '', !empty && mono ? 'gh-mono' : ''].filter(Boolean).join(' ') || undefined}>
              {empty ? '-' : String(value)}
            </dd>,
          ];
        })}
      </dl>

      <div className="dict-detail-columns">
        <h4>{data && columns.length > 0 ? `컬럼 목록 (${columns.length})` : '컬럼 목록'}</h4>
        {error ? (
          <p className="gh-muted">{error instanceof ApiError && error.status === 403 ? '컬럼 목록을 조회할 권한이 없습니다.' : '컬럼 목록을 불러오지 못했습니다.'}</p>
        ) : !data ? (
          <p className="gh-muted">컬럼을 조회하고 있습니다.</p>
        ) : columns.length === 0 ? (
          <p className="gh-muted">등록된 컬럼이 없습니다.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {['#', '컬럼명', '컬럼 설명', '데이터 타입', 'PK', 'NULL', '승인'].map((h) => <th key={h}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {columns.map((col, idx) => (
                  <tr key={col.id}>
                    <td className="text-center">{idx + 1}</td>
                    <td className="gh-mono">{col.columnName ?? '-'}</td>
                    <td>{col.columnDesc ?? '-'}</td>
                    <td className="gh-mono">{col.dataType ? `${col.dataType}${col.maxLength ? `(${col.maxLength})` : ''}` : '-'}</td>
                    <td className="text-center">{ynLabel(col.isPk, 'PK')}</td>
                    <td className="text-center">{ynLabel(col.isNullable, 'Y')}</td>
                    <td className="text-center">{ynLabel(col.isApproval, '승인', 'success')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="gh-dialog-actions">
        {/* 컬럼 일괄 편집 : 승인된 테이블에만 컬럼을 등록할 수 있다 */}
        <button type="button" className="btn" disabled={!approved} onClick={onEditColumns}
          title={approved ? '표준용어로 컬럼을 한 번에 추가/수정/삭제합니다.' : '승인된 테이블에만 컬럼을 등록할 수 있습니다.'}>
          컬럼 편집
        </button>
        {/* DDL 은 승인 여부와 관계없이 볼 수 있다 */}
        <button type="button" className="btn" onClick={onDdl}>DDL 보기</button>
        {onEdit && <button type="button" className="btn btn-primary" onClick={onEdit}>수정</button>}
      </div>
    </div>
  );
}
