import { useRef, useState, type ReactNode, type RefObject } from 'react';
import { useDialog, type DialogApi, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Mono, YN } from '../../components/grid/cells';
import { Grid, type GridColumn, type GridHandle, type Row } from '../../components/grid/Grid';
import { BookIcon, DownloadIcon, RowsIcon, TagIcon, UploadIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, download } from '../../lib/api';
import { DictionaryDetail } from './DictionaryDetail';
import { DictionaryUsage } from './DictionaryUsage';
import { DictionaryForm, fieldsFromColumns, fieldsFromRow, type DictionaryTable, type FormConfig } from './DictionaryForm';
import { TermCreateForm } from './TermCreateForm';
import { standardDomainsColumns, standardWordsColumns, standardTermsColumns } from './columns';
import { t } from '../../lib/i18n';

const DB_DATA_TYPES = [
  'VARCHAR', 'CHAR', 'TEXT', 'CLOB',
  'INT', 'INTEGER', 'BIGINT', 'SMALLINT',
  'FLOAT', 'DOUBLE', 'DECIMAL', 'NUMERIC',
  'DATE', 'DATETIME', 'TIMESTAMP', 'TIME',
  'BOOLEAN', 'BIT',
  'BLOB', 'BINARY', 'VARBINARY', 'LONGBLOB', 'MEDIUMBLOB',
  'JSON', 'ENUM', 'SET',
].map((t) => ({ value: t, text: t }));

const YN_OPTIONS = [
  { value: 'Y', text: 'TRUE' },
  { value: 'N', text: 'FALSE' },
];

interface TabDef {
  id: DictionaryTable;
  target: string;
  label: string;
  icon: ReactNode;
  name: string; // 알림 문구용 (표준 도메인 / 표준 단어 / 표준 용어)
  title: string; // 다이얼로그 제목용 (표준도메인 / 표준단어 / 표준용어)
  dataUrl: string;
  columns: GridColumn[];
  approvalUrl: string;
}

const TABS: TabDef[] = [
  { id: 'standardDomains', target: 'standard-domains', label: t('표준 도메인'), icon: <RowsIcon />, name: t('표준 도메인'), title: t('표준도메인'), dataUrl: '/api/getStandardDomains', columns: standardDomainsColumns, approvalUrl: '/api/approvalStandardDomains' },
  { id: 'standardWords', target: 'standard-words', label: t('표준 단어'), icon: <TagIcon />, name: t('표준 단어'), title: t('표준단어'), dataUrl: '/api/getStandardWords', columns: standardWordsColumns, approvalUrl: '/api/approvalStandardWords' },
  { id: 'standardTerms', target: 'standard-terms', label: t('표준 용어'), icon: <BookIcon />, name: t('표준 용어'), title: t('표준용어'), dataUrl: '/api/getStandardTerms', columns: standardTermsColumns, approvalUrl: '/api/approvalStandardTerms' },
];

/** 탭별 등록/수정 폼 규칙 (예전 standard_domains.js / standard_words.js / standard_terms.js) */
const FORM_CONFIG: Record<DictionaryTable, { add?: FormConfig; edit: FormConfig }> = {
  standardDomains: {
    add: {
      control: (k) => (['commonStandardDomainDescription', 'allowedValues'].includes(k) ? 'textarea'
        : ['revisionNumber', 'dataLength', 'dataDecimalLength'].includes(k) ? 'number'
          : k === 'dataType' ? { options: DB_DATA_TYPES } : 'text'),
      required: (k) => !['allowedValues', 'revisionNumber'].includes(k),
    },
    edit: {
      control: (k) => (['revisionNumber', 'dataLength', 'dataDecimalLength'].includes(k) ? 'number' : k === 'dataType' ? { options: DB_DATA_TYPES } : 'text'),
      readonly: (k) => ['allowedValues', 'commonStandardDomainName', 'storageFormat', 'displayFormat', 'commonStandardDomainGroupName', 'commonStandardDomainCategory'].includes(k),
      required: (k) => !['allowedValues', 'revisionNumber'].includes(k),
    },
  },
  standardWords: {
    add: {
      control: (k) => (['commonStandardDomainDescription', 'allowedValues'].includes(k) ? 'textarea'
        : ['useAbbreviation', 'isFormatWord'].includes(k) ? { options: YN_OPTIONS }
          : k === 'revisionNumber' ? 'number' : 'text'),
      required: (k) => !['commonStandardDomainCategory', 'synonyms', 'restrictedWords', 'revisionNumber'].includes(k),
      // '_' 는 표준용어의 단어 구분자라 단어명/영문약어명에 쓸 수 없다. (서버에서도 검증)
      validate: (data) => (['commonStandardWordName', 'commonStandardWordAbbreviation'].some((k) => String(data[k] ?? '').includes('_'))
        ? t('표준단어명과 표준단어영문약어명에는 \'_\' 를 쓸 수 없습니다.\n\'_\' 는 표준용어에서 단어를 나누는 구분자입니다. (예: 사용자_이름 / USER_NM)')
        : null),
    },
    edit: {
      control: (k) => (['useAbbreviation', 'isFormatWord'].includes(k) ? { options: YN_OPTIONS } : k === 'revisionNumber' ? 'number' : 'text'),
      readonly: (k) => ['commonStandardWordName', 'commonStandardWordAbbreviation', 'commonStandardWordEnglishName', 'commonStandardDomainCategory', 'synonyms'].includes(k),
      required: (k) => !['commonStandardDomainCategory', 'synonyms', 'restrictedWords', 'revisionNumber'].includes(k),
    },
  },
  standardTerms: {
    edit: {
      control: (k) => (['commonStandardTermDescription', 'synonyms'].includes(k) ? 'textarea' : k === 'revisionNumber' ? 'number' : 'text'),
      readonly: (k) => ['commonStandardDomainName', 'allowedValues', 'storageFormat', 'displayFormat', 'synonyms', 'commonStandardTermName', 'commonStandardTermAbbreviation'].includes(k),
      required: (k) => ['commonStandardDomainName', 'allowedValues', 'storageFormat', 'displayFormat', 'commonStandardTermName', 'commonStandardTermDescription'].includes(k),
    },
  },
};

/** 데이터 사전 : 표준 도메인 / 단어 / 용어 관리, 엑셀 업로드·다운로드 */
export function DataDictionaryPage() {
  const dialog = useDialog();
  // 다른 화면(물리 ERD)에서 넘어온 경우 : ?tab=standardTerms&searchColumn=...&searchQuery=... 로 그 탭을 검색한 상태로 연다
  const [linked] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    const tab = TABS.find((candidate) => candidate.id === params.get('tab'));
    const column = params.get('searchColumn');
    const query = params.get('searchQuery');
    return tab ? { tab: tab.id, search: column && query ? { column, query } : undefined } : null;
  });
  const [active, setActive] = useState<DictionaryTable>(linked?.tab ?? 'standardDomains');
  // 탭은 처음 열 때 그리드를 만들고 이후에는 유지한다 (검색 조건/페이지 유지)
  const [visited, setVisited] = useState<Set<DictionaryTable>>(new Set([linked?.tab ?? 'standardDomains']));
  const grids = {
    standardDomains: useRef<GridHandle>(null),
    standardWords: useRef<GridHandle>(null),
    standardTerms: useRef<GridHandle>(null),
  };
  const fileRef = useRef<HTMLInputElement>(null);

  const openTemplateDialog = () => {
    dialog.open({
      title: t('템플릿 다운로드'),
      content: ({ close }: DialogControls) => (
        <div className="gh-action-list">
          <button type="button" id="download-data-portal" onClick={() => download('/api/downloadTemplate/dataPortal', t('공통표준용어.xlsx')).then(close)}>
            <DownloadIcon />
            <div>
              <strong>{t('공통표준용어 다운로드')}</strong>
              <span>{t('공공데이터포털의 공통표준용어 파일을 내려받습니다.')}</span>
            </div>
          </button>
          <button type="button" id="download-template" onClick={() => download('/api/downloadTemplate/template', t('데이터사전 템플릿.xlsx')).then(close)}>
            <DownloadIcon />
            <div>
              <strong>{t('빈 템플릿 다운로드')}</strong>
              <span>{t('데이터사전 업로드용 엑셀 양식을 내려받습니다.')}</span>
            </div>
          </button>
        </div>
      ),
    });
  };

  const onFile = () => {
    const file = fileRef.current?.files?.[0];
    if (!file) return;
    dialog.confirm(
      t('이 프로젝트에서 데이터 사전 약어 사용 여부가 ‘사용 안 함’으로 설정되지 않은 경우, 해당 표준단어 영문명이 여러 단어로 구성되어 있어 약어가 자동 적용됩니다. 계속 진행하시겠습니까?'),
      async () => {
        const formData = new FormData();
        formData.append('file', file);
        try {
          const response = await api<{ result: string }>('/api/uploadDataDictionaryExcelFile', { method: 'POST', body: formData });
          if (response.result === 'success') {
            dialog.alert(t('업로드가 완료되었습니다.'));
            Object.values(grids).forEach((g) => g.current?.resetSearch());
          }
        } finally {
          if (fileRef.current) fileRef.current.value = '';
        }
      },
      () => {
        if (fileRef.current) fileRef.current.value = '';
      },
    );
  };

  const extraActions = (
    <>
      <button type="button" className="btn" onClick={openTemplateDialog}><DownloadIcon />{t('템플릿')}</button>
      <button type="button" id="download-dictionary" className="btn" onClick={() => download('/api/downloadTemplate/dictionary', t('데이터 사전.xlsx'))}>
        <DownloadIcon />{t('등록 항목 다운로드')}
      </button>
      <button type="button" id="excel-upload" className="btn" onClick={() => fileRef.current?.click()}><UploadIcon />{t('업로드')}</button>
    </>
  );

  return (
    <>
      <PageHeader category={t('데이터 관리')} title={t('데이터 사전')} description={t('프로젝트의 표준 도메인·단어·용어를 관리합니다. 엑셀로 한 번에 등록하거나 등록된 항목을 내려받을 수 있습니다.')} />
      <input type="file" ref={fileRef} style={{ display: 'none' }} accept=".xlsx, .xls" onChange={onFile} />

      <ul className="nav nav-tabs">
        {TABS.map((tab) => (
          <li className="nav-item" key={tab.id}>
            <a href={`#${tab.target}`} className={`nav-link${active === tab.id ? ' active' : ''}`} id={`${tab.target}-tab`}
              onClick={(e) => {
                e.preventDefault();
                setActive(tab.id);
                setVisited((v) => new Set(v).add(tab.id));
              }}>
              {tab.icon}{tab.label}
            </a>
          </li>
        ))}
      </ul>

      <div className="tab-content mt-3">
        {TABS.map((tab) => (
          <div key={tab.id} className={`tab-pane fade${active === tab.id ? ' show active' : ''}`} id={tab.target}>
            {visited.has(tab.id) && (
              <DictionaryGrid tab={tab} gridRef={grids[tab.id]} dialog={dialog} extraActions={active === tab.id ? extraActions : undefined}
                initialSearch={linked?.tab === tab.id ? linked.search : undefined} />
            )}
          </div>
        ))}
      </div>
    </>
  );
}

function DictionaryGrid({ tab, gridRef, dialog, extraActions, initialSearch }: {
  tab: TabDef; gridRef: RefObject<GridHandle | null>; dialog: DialogApi; extraActions?: ReactNode; initialSearch?: { column: string; query: string };
}) {
  const reload = () => gridRef.current?.reload();

  const approve = (isApproval: boolean) => {
    const checked = gridRef.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm(isApproval ? t('체크된 {0}를 승인하시겠습니까?', tab.name) : t('체크된 {0}를 승인취소하시겠습니까?', tab.name), async () => {
      // 승인(취소) 필요 대상이 하나라도 있어야 한다
      if (!checked.some((e) => e.isApprovalYn === (isApproval ? 'N' : 'Y'))) {
        dialog.alert(isApproval ? t('승인이 필요한 {0}가 선택되지 않았습니다.', tab.name) : t('승인취소가 필요한 {0}가 선택되지 않았습니다.', tab.name));
        return;
      }
      const response = await api<{ result: boolean }>(`${tab.approvalUrl}/${isApproval}`, { method: 'PATCH', body: checked });
      if (response.result) dialog.alert(isApproval ? t('정상적으로 승인처리 되었습니다.') : t('정상적으로 승인취소처리 되었습니다.'), reload);
    });
  };

  const remove = () => {
    const checked = gridRef.current?.getCheckedOrAlert();
    if (!checked) return;
    if (checked.some((e) => e.isApprovalYn === 'Y')) {
      dialog.alert(t('체크된 데이터에 승인된 정보도 포함되어있습니다. 승인이 완료된 경우, 삭제가 불가능합니다.'));
      return;
    }
    dialog.confirm(t('체크된 데이터를 삭제하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>(`/api/deleteDataDictionary/${tab.id}`, { method: 'DELETE', body: checked });
      if (response.result) dialog.alert(t('정상적으로 삭제처리 되었습니다.'), reload);
    });
  };

  const add = () => {
    if (tab.id === 'standardTerms') {
      dialog.open({
        title: t('표준용어 추가'),
        content: ({ close }: DialogControls) => (
          <TermCreateForm onSaved={() => dialog.alert(t('정상적으로 등록되었습니다.'), () => { close(); reload(); })} />
        ),
      });
      return;
    }
    const columns = gridRef.current?.columns ?? [];
    dialog.open({
      title: t('{0} 추가', tab.title),
      content: ({ close }: DialogControls) => (
        <DictionaryForm tableId={tab.id} type="C" fields={fieldsFromColumns(columns)} config={FORM_CONFIG[tab.id].add!} submitText={t('추가')}
          onSaved={() => { close(); reload(); }} />
      ),
    });
  };

  const openEdit = (row: Row, columns: GridColumn[]) => {
    dialog.open({
      title: t('{0} 수정', tab.title),
      content: ({ close }: DialogControls) => (
        <DictionaryForm tableId={tab.id} type="U" fields={fieldsFromRow(row, columns, ['isApprovalYn', 'usageCount'])} config={FORM_CONFIG[tab.id].edit} submitText={t('저장')}
          onSaved={() => { close(); reload(); }} />
      ),
    });
  };

  // 사용 현황 : 이 표준 항목을 실제로 참조하는 컬럼(용어·도메인) / 표준 용어(단어)
  const openUsage = (row: Row) => {
    const name = row.commonStandardTermName ?? row.commonStandardDomainName ?? row.commonStandardWordName ?? '';
    dialog.open({
      title: t('사용 현황 · {0}', name),
      className: 'dialog-usage',
      content: ({ close }: DialogControls) => <DictionaryUsage kind={tab.id} row={row} close={close} />,
    });
  };

  // 행 선택 : 그리드에서 숨긴 항목까지 상세보기로 보여주고, 승인 전 항목만 수정 팝업으로 이동한다.
  const onRowClick = (row: Row, columns: GridColumn[]) => {
    dialog.open({
      title: t('{0} 상세', tab.title),
      content: ({ close }: DialogControls) => (
        <DictionaryDetail row={row} columns={columns} onEdit={row.isApprovalYn === 'Y' ? undefined : () => { close(); openEdit(row, columns); }}
          onUsage={() => { close(); openUsage(row); }} />
      ),
    });
  };

  const renderCell = (field: string, row: Row): ReactNode | undefined => {
    if (field === 'isApprovalYn') return <Approval value={row.isApprovalYn} />;
    if (field === 'usageCount') {
      return <button type="button" className="btn btn-sm" title={t('사용 현황 보기')} onClick={() => openUsage(row)}>{row.usageCount ?? 0}</button>;
    }
    if (tab.id === 'standardDomains' && field === 'dataType') return <Mono>{row.dataType}</Mono>;
    if (tab.id === 'standardWords') {
      if (field === 'commonStandardWordAbbreviation') return <Mono>{row.commonStandardWordAbbreviation}</Mono>;
      if (field === 'useAbbreviation' || field === 'isFormatWord') return <YN value={row[field]} />;
    }
    if (tab.id === 'standardTerms' && field === 'commonStandardTermAbbreviation') return <Mono>{row.commonStandardTermAbbreviation}</Mono>;
    return undefined;
  };

  return (
    <Grid
      ref={gridRef}
      id={tab.id}
      columns={tab.columns}
      dataUrl={tab.dataUrl}
      initialSearch={initialSearch}
      crudActions="anCD"
      extraActions={extraActions}
      onAction={{ active: () => approve(true), unactive: () => approve(false), add, delete: remove }}
      onRowClick={onRowClick}
      renderCell={renderCell}
    />
  );
}
