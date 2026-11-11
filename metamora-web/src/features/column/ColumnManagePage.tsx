import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Mono, YN } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { DownloadIcon, UploadIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, download, enc } from '../../lib/api';
import { isBlank } from '../../lib/format';
import { ColumnForm } from './ColumnForm';
import { ColumnOrder } from './ColumnOrder';
import { columnColumns } from './columns';
import { t } from '../../lib/i18n';

/** 컬럼 상세 : 값 항목은 [라벨, 키, 코드글꼴 여부], 속성 항목(Y/N)은 라벨 목록 */
const DETAIL_FIELDS: [string, string, boolean?][] = [
  [t('스키마'), 'schemaName', true],
  [t('테이블명'), 'tableName', true],
  [t('컬럼명'), 'columnName', true],
  [t('컬럼 설명'), 'columnDesc'],
  [t('데이터 타입'), 'dataType', true],
  [t('최대 길이'), 'maxLength'],
  [t('정밀도'), 'precision'],
  [t('소수 자릿수'), 'scale'],
  [t('기본값'), 'defaultValue', true],
  [t('정렬 순서'), 'sortOrder'],
  [t('참조 테이블명'), 'refTableName', true],
  [t('데이터 예시'), 'example'],
];

const DETAIL_FLAGS: [string, string][] = [
  ['PK', 'isPk'],
  [t('NULL 허용'), 'isNullable'],
  [t('필수 입력'), 'isRequired'],
  [t('고유값'), 'isUnique'],
  [t('인덱스'), 'isIndex'],
  [t('마스터 데이터'), 'isMasterData'],
  [t('민감정보'), 'isSensitive'],
  [t('암호화'), 'isEncrypted'],
];

/** 컬럼 관리 : 테이블별 컬럼 정보를 표준 용어 기준으로 등록·승인 */
export function ColumnManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const { canView } = useAuth();
  const reload = () => grid.current?.reload();

  const add = () => {
    dialog.open({
      title: t('컬럼 등록'),
      content: ({ close }: DialogControls) => <ColumnForm onSaved={() => { close(); reload(); }} />,
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
      const response = await api<{ result: boolean }>(`/api/saveColumn/approval/${isApproval}`, { method: 'PATCH', body: checked });
      if (response.result) dialog.alert(isApproval ? t('정상적으로 승인처리 되었습니다.') : t('정상적으로 승인취소처리 되었습니다.'), reload);
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
      const response = await api<{ result: boolean }>('/api/deleteColumn', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert(t('정상적으로 삭제되었습니다.'), reload);
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
        const response = await api<{ result: string }>('/api/uploadColumnExcelFile', { method: 'POST', body: formData });
        if (response.result === 'success') {
          dialog.alert(t('업로드가 완료되었습니다.'));
          reload();
        }
      } finally {
        clear();
      }
    }, clear);
  };

  // 같은 테이블의 승인 전 컬럼 순서 변경
  const openColumnOrder = async (row: Row) => {
    const response = await api<{ result: boolean; columnInfos?: Row[] }>(`/api/selectColumn/detail/${enc(row.id)}`);
    if (!response.result) return;
    const columnInfos = response.columnInfos ?? [];
    if (columnInfos.length === 0) return dialog.alert(t('승인된 컬럼이 존재하여 수정이 불가능합니다.'));
    dialog.popup({
      title: t('컬럼 순서 · {0}', columnInfos[0].tableName ?? ''),
      size: 'large',
      content: (controls: DialogControls) => (
        <ColumnOrder columnInfos={columnInfos} controls={controls} onSuccess={() => dialog.alert(t('정상적으로 순서가 저장되었습니다.'), reload)} />
      ),
    });
  };

  // 행 선택 : 그리드에서 숨긴 항목까지 상세로 보여주고, 승인 전 컬럼은 [컬럼 순서 변경]
  const openDetail = (row: Row) => {
    const approved = row.isApproval === 'Y';
    dialog.open({
      title: t('컬럼 상세 · {0}.{1}', row.tableName ?? '', row.columnName ?? ''),
      content: ({ close }: DialogControls) => (
        <div>
          <div className="dict-detail-status">
            <span className={`gh-label gh-label-dot ${approved ? 'gh-label-success' : 'gh-label-attention'}`}>{approved ? t('승인') : t('승인 대기')}</span>
            <span className="gh-muted">{approved ? t('승인된 컬럼은 수정할 수 없습니다.') : ''}</span>
          </div>
          <dl className="dict-detail">
            {DETAIL_FIELDS.map(([label, key, mono]) => {
              const value = row[key];
              const empty = isBlank(value);
              return [
                <dt key={`t-${key}`}>{label}</dt>,
                <dd key={`d-${key}`} className={[empty ? 'gh-muted' : '', !empty && mono ? 'gh-mono' : ''].filter(Boolean).join(' ') || undefined}>
                  {empty ? '-' : String(value)}
                </dd>,
              ];
            })}
            <dt>{t('속성')}</dt>
            <dd>
              <div className="dict-detail-flags">
                {DETAIL_FLAGS.map(([label, key]) => {
                  const on = row[key] === 'Y';
                  return (
                    <span key={key} className={`gh-label ${on ? 'gh-label-accent' : 'dict-detail-flag-off'}`} title={`${label} : ${on ? 'Y' : 'N'}`}>{label}</span>
                  );
                })}
              </div>
            </dd>
          </dl>
          <div className="gh-dialog-actions">
            {canView('physical-erd') && row.tableInfoId != null && (
              <button type="button" className="btn" onClick={() => { close(); navigate(`/physicalErd?schemaId=${enc(row.schemaId)}&tableId=${enc(row.tableInfoId)}`); }}>
                {t('ERD 에서 보기')}
              </button>
            )}
            {!approved && <button type="button" className="btn" onClick={() => { close(); openColumnOrder(row); }}>{t('컬럼 순서 변경')}</button>}
          </div>
        </div>
      ),
    });
  };

  return (
    <>
      <PageHeader category={t('데이터 관리')} title={t('컬럼 관리')} description={t('테이블별 컬럼 정보를 표준 용어 기준으로 등록하고 승인합니다.')} />
      <input type="file" ref={fileRef} style={{ display: 'none' }} accept=".xlsx, .xls" onChange={onFile} />

      <div id="gridContainer">
        <Grid
          ref={grid}
          id="columnManageGrid"
          columns={columnColumns}
          dataUrl="/api/selectColumn"
          crudActions="anCD"
          extraActions={
            <>
              <button type="button" className="btn" id="download-template" onClick={() => download('/api/downloadTemplate/columnTemplate', t('컬럼 템플릿.xlsx'))}>
                <DownloadIcon />{t('템플릿')}
              </button>
              <button type="button" id="excel-upload" className="btn" onClick={() => fileRef.current?.click()}><UploadIcon />{t('업로드')}</button>
            </>
          }
          onAction={{ add, active: () => updateActive(true), unactive: () => updateActive(false), delete: remove }}
          onRowClick={openDetail}
          renderCell={(field, row) => {
            if (['schemaName', 'tableName', 'columnName', 'dataType'].includes(field)) return <Mono>{row[field]}</Mono>;
            if (field === 'isPk') return <YN value={row.isPk} text="PK" />;
            if (field === 'isNullable') return <YN value={row.isNullable} />;
            if (field === 'isApproval') return <Approval value={row.isApproval} />;
            return undefined;
          }}
        />
      </div>
    </>
  );
}
