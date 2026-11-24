import { useRef, useState } from 'react';
import { ObjectBizInfo } from '../dictionary/ai/BizDescriptionInfo';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { Approval, Mono, YN } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { DownloadIcon, UploadIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, download, enc } from '../../lib/api';
import { approveColumns, deleteColumns } from '../table/physicalConflicts';
import { isBlank } from '../../lib/format';
import { ColumnForm } from './ColumnForm';
import { ColumnOrder } from './ColumnOrder';
import { TermRelink, type TermRelinkColumn } from './TermRelink';
import { BulkTermLink, type BulkTermColumn } from './BulkTermLink';
import { termLabel } from './terms';
import { ColumnRecommendationNote } from '../mapping/MappingRecommendation';
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
  const toast = useToast();
  const grid = useRef<GridHandle>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const { canView } = useAuth();
  const reload = () => grid.current?.reload();
  // 다른 화면에서 넘어온 검색 조건 (?searchColumn=tableName&searchQuery=…) — 데이터 사전과 같은 방식
  const [initialSearch] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    const column = params.get('searchColumn');
    const query = params.get('searchQuery');
    return column && query ? { column, query } : undefined;
  });

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
      const done = () => toast.success(isApproval ? t('정상적으로 승인처리 되었습니다.') : t('정상적으로 승인취소처리 되었습니다.'));
      // 409 (M8-C3) : 바뀐 컬럼만 보여 주고 최신 내용으로 다시 승인할지 묻는다
      if (await approveColumns(dialog, checked, isApproval, reload, done)) {
        reload();
        done();
      }
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
      const done = () => toast.success(t('정상적으로 삭제되었습니다.'));
      if (await deleteColumns(dialog, checked, reload, done)) {
        reload();
        done();
      }
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
        <ColumnOrder columnInfos={columnInfos} controls={controls} onSuccess={() => { reload(); toast.success(t('정상적으로 순서가 저장되었습니다.')); }} />
      ),
    });
  };

  // 표준 용어 재연결 (COL-TERM) : 승인 전 컬럼, 용어가 연결된 컬럼만. 연결만 바꾸고 컬럼 속성은 그대로
  const openTermRelink = (row: Row) => {
    dialog.popup({
      title: row.standardTerms ? t('표준 용어 변경 · {0}.{1}', row.tableName ?? '', row.columnName ?? '') : t('표준 용어 연결 · {0}.{1}', row.tableName ?? '', row.columnName ?? ''),
      content: ({ close }: DialogControls) => (
        <TermRelink column={row as unknown as TermRelinkColumn} onCancel={close}
                    onChanged={(changed) => { close(); reload(); if (changed) toast.success(t('표준 용어 연결을 바꿨습니다.')); else toast.info(t('같은 표준 용어라 바꾸지 않았습니다.')); }} />
      ),
    });
  };

  // 표준 용어 일괄 연결·변경 (DEC-26) : 체크한 컬럼마다 같은 영문 약어의 용어를 찾아 계획을 보이고 한 번에 반영
  const openBulkTermLink = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.popup({
      title: t('표준 용어 일괄 연결 ({0}개)', checked.length),
      content: ({ close }: DialogControls) => (
        <BulkTermLink columns={checked as unknown as BulkTermColumn[]} onCancel={close}
                      onDone={(changed) => { close(); reload(); toast.success(t('{0}개 컬럼의 표준 용어 연결을 바꿨습니다.', changed)); }} />
      ),
    });
  };

  // 행 선택 : 그리드에서 숨긴 항목까지 상세로 보여주고, 승인 전 컬럼은 [컬럼 순서 변경]
  const openDetail = (row: Row) => {
    const approved = row.isApproval === 'Y';
    const term = row.standardTerms as TermRelinkColumn['standardTerms'] | null | undefined;
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
            <dt>{t('표준 용어')}</dt>
            <dd className={term?.id ? undefined : 'gh-muted'}>
              {term?.id ? <>{termLabel(term)} <span className="gh-mono gh-muted">{term.commonStandardTermAbbreviation}</span>{term.commonStandardDomainName && <span className="gh-muted"> · {term.commonStandardDomainName}</span>}</> : '-'}
            </dd>
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
          {/* 논리 속성에 매핑된 컬럼이면 표준 용어 기준의 추천 컬럼명을 보여 준다 (조회만, 이름은 바꾸지 않는다) */}
          <ColumnRecommendationNote columnId={row.id} onNavigate={(path) => { close(); navigate(path); }} />
          {/* 업무 설명과 AI 설명 제안 상태 (AI-5) — 제안으로 이동하면 그 컬럼이 선택된다 */}
          <ObjectBizInfo tableId={row.tableInfoId as number | undefined} columnId={row.id as number} />
          <div className="gh-dialog-actions">
            {canView('physical-erd') && row.tableInfoId != null && (
              <button type="button" className="btn" onClick={() => { close(); navigate(`/physicalErd?schemaId=${enc(row.schemaId)}&tableId=${enc(row.tableInfoId)}`); }}>
                {t('ERD 에서 보기')}
              </button>
            )}
            {!approved && canView('physical-model-edit') && (
              <button type="button" className="btn" onClick={() => { close(); openTermRelink(row); }}>{term?.id != null ? t('표준 용어 변경') : t('표준 용어 연결하기')}</button>
            )}
            {!approved && <button type="button" className="btn" onClick={() => { close(); openColumnOrder(row); }}>{t('컬럼 순서 변경')}</button>}
          </div>
        </div>
      ),
    });
  };

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('컬럼 관리')} description={t('테이블별 컬럼 정보를 표준 용어 기준으로 등록하고 승인합니다.')} />
      <input type="file" ref={fileRef} style={{ display: 'none' }} accept=".xlsx, .xls" onChange={onFile} />

      <div id="gridContainer">
        <Grid
          ref={grid}
          id="columnManageGrid"
          columns={columnColumns}
          dataUrl="/api/selectColumn"
          initialSearch={initialSearch}
          crudActions="anCD"
          extraActions={
            <>
              <button type="button" className="btn" id="download-template" title={t('값이 없는 업로드 양식을 내려받습니다.')}
                onClick={() => download('/api/downloadTemplate/columnTemplate', t('컬럼 템플릿.xlsx'))}>
                <DownloadIcon />{t('빈 템플릿')}
              </button>
              {/* 등록 데이터 (M-11) : 지금 검색 조건 전체, 페이지와 무관. 업로드 양식과 같은 열 + 뒤에 참고 열 */}
              <button type="button" className="btn" id="download-data" title={t('지금 조회 조건의 등록 데이터 전체를 업로드 양식으로 내려받습니다. 뒤쪽 참고 열은 다시 올려도 반영되지 않습니다.')}
                onClick={() => {
                  const search = grid.current?.getSearch();
                  const query = new URLSearchParams(search?.column ? { searchColumn: search.column, searchQuery: search.query } : {});
                  return download(`/api/exportExcel/columns?${query}`, t('컬럼 등록 데이터.xlsx'));
                }}>
                <DownloadIcon />{t('등록 데이터')}
              </button>
              <button type="button" id="excel-upload" className="btn" onClick={() => fileRef.current?.click()}><UploadIcon />{t('업로드')}</button>
              {canView('physical-model-edit') && <button type="button" id="bulk-term-link" className="btn" onClick={openBulkTermLink}>{t('표준 용어 일괄 연결')}</button>}
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
