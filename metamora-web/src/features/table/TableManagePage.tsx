import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Mono, YN } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { DownloadIcon, UploadIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, download, enc } from '../../lib/api';
import { useAuth } from '../../hooks/useAuth';
import { useSchemas } from '../schema/schemas';
import { TableForm } from './TableForm';
import { tableColumns } from './columns';
import { useTableDetail } from './tableDetail';
import { t } from '../../lib/i18n';

/** 테이블 관리 : 테이블 설계 정보 등록·승인, 상세(컬럼 목록 / 컬럼 일괄 편집 / DDL) */
export function TableManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const { openTableDetail } = useTableDetail();
  const navigate = useNavigate();
  const { canView } = useAuth();
  const { schemas, refresh: refreshSchemas } = useSchemas();
  // 스키마별 조회 : '' = 전체 스키마
  const [schemaId, setSchemaId] = useState('');
  const reload = () => {
    grid.current?.reload();
    void refreshSchemas(); // 스키마별 테이블 수
  };

  const openForm = (type: 'C' | 'U', row?: Row) => {
    dialog.open({
      title: type === 'C' ? t('테이블 등록') : t('테이블 수정'),
      content: ({ close }: DialogControls) => <TableForm type={type} row={row} schemas={schemas} onSaved={() => { close(); reload(); }} />,
    });
  };

  const remove = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    if (checked.some((e) => e.isApproval === 'Y')) {
      dialog.alert(t('체크된 데이터에 승인된 정보도 포함되어있습니다. 승인이 완료된 경우, 삭제가 불가능합니다.'));
      return;
    }
    dialog.confirm(t('체크된 데이터를 삭제하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>('/api/deleteTable', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert(t('정상적으로 삭제되었습니다.'), reload);
    });
  };

  const updateActive = (isApproval: boolean) => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm(isApproval ? t('체크된 테이블 정보를 승인하시겠습니까?') : t('체크된 테이블 정보를 승인취소하시겠습니까?'), async () => {
      if (!checked.some((e) => e.isApproval === (isApproval ? 'N' : 'Y'))) {
        dialog.alert(isApproval ? t('승인 필요한 정보가 선택되지 않았습니다.') : t('승인취소 필요한 정보가 선택되지 않았습니다.'));
        return;
      }
      const response = await api<{ result: boolean }>(`/api/saveTable/${isApproval}`, { method: 'PATCH', body: checked });
      if (response.result) dialog.alert(isApproval ? t('정상적으로 승인처리 되었습니다.') : t('정상적으로 승인취소처리 되었습니다.'), reload);
    });
  };

  const onFile = () => {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    const clear = () => {
      if (fileRef.current) fileRef.current.value = '';
    };
    dialog.confirm(t('업로드 하시겠습니까?'), async () => {
      const formData = new FormData();
      formData.append('file', file);
      try {
        const response = await api<{ result: string }>('/api/uploadTableExcelFile', { method: 'POST', body: formData });
        if (response.result === 'success') {
          dialog.alert(t('업로드가 완료되었습니다.'));
          reload();
        }
      } finally {
        clear();
      }
    }, clear);
  };

  // 행 선택 : 테이블 상세 (그리드에서 숨긴 항목 + 등록된 컬럼 목록, 컬럼 편집 / DDL / 승인 / 수정)
  const openDetail = (row: Row) => openTableDetail(row, {
    onChanged: reload,
    onErd: canView('physical-erd') ? () => navigate(`/physicalErd?schemaId=${enc(row.schemaId)}&tableId=${enc(row.id)}`) : undefined,
  });

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('테이블 관리')}
        description={t('테이블 설계 정보를 등록하고 승인합니다. 테이블명은 한글명(예: 사용_내역_관리)을 승인된 표준 단어의 영문약어로 바꿔 만듭니다. 행을 누르면 테이블 정보를 수정할 수 있습니다.')} />
      <input type="file" ref={fileRef} style={{ display: 'none' }} accept=".xlsx, .xls" onChange={onFile} />

      <div id="gridContainer">
        <Grid
          ref={grid}
          id="tableManageGrid"
          columns={tableColumns}
          dataUrl="/api/selectTable"
          params={schemaId ? { schemaId } : undefined}
          searchFilters={
            <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
              <option value="">{t('전체 스키마')}</option>
              {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
            </select>
          }
          crudActions="anCD"
          extraActions={
            <>
              <button type="button" className="btn" id="download-template" onClick={() => download('/api/downloadTemplate/tableTemplate', t('테이블 템플릿.xlsx'))}>
                <DownloadIcon />{t('템플릿')}
              </button>
              <button type="button" id="excel-upload" className="btn" onClick={() => fileRef.current?.click()}><UploadIcon />{t('업로드')}</button>
            </>
          }
          onAction={{ add: () => openForm('C'), delete: remove, active: () => updateActive(true), unactive: () => updateActive(false) }}
          onRowClick={openDetail}
          renderCell={(field, row) => {
            if (field === 'tableName' || field === 'schemaName') return <Mono>{row[field]}</Mono>;
            if (field === 'isMasterTable') return <YN value={row.isMasterTable} text={t('마스터')} />;
            if (field === 'isApproval') return <Approval value={row.isApproval} />;
            return undefined;
          }}
        />
      </div>
    </>
  );
}
