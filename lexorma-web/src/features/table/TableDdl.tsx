import { useState, type FormEvent } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api, ApiError, enc } from '../../lib/api';
import { t } from '../../lib/i18n';

export interface TableDdl {
  dbType: string;
  dbTypeLabel?: string;
  ddl: string;
  warnings?: string[];
  /** DDL 에 들어 있는 승인 대기 항목 (종류별 한 줄) */
  pendingApprovals?: string[];
}

/** 테이블 DDL : 프로젝트 대상 DB(시스템 관리 > 프로젝트 관리) 기준으로 서버에서 생성한 DDL. ddlUrl 을 주면 그 DDL (예: 인덱스 하나) */
/** downloadName : 주면 [내보내기] 로 DDL 을 .sql 파일로 저장한다 (VIEW DDL 내보내기) */
export function TableDdlView({ tableId, ddlUrl, downloadName }: { tableId?: number; ddlUrl?: string; downloadName?: string }) {
  const url = ddlUrl ?? `/api/selectTable/ddl/${enc(tableId)}`;
  const dialog = useDialog();
  const [copied, setCopied] = useState(false);
  // 스키마명 포함 : 기본 스키마 테이블에 붙일 실제 스키마명. [적용] 을 눌러야 DDL 에 반영한다 (다른 스키마의 테이블은 항상 스키마명이 붙는다)
  const [withSchema, setWithSchema] = useState(false);
  const [schemaInput, setSchemaInput] = useState('');
  const [schemaName, setSchemaName] = useState('');
  const { data, error } = useQuery({
    queryKey: ['table-ddl', url, schemaName],
    queryFn: () => api<TableDdl>(`${url}${schemaName ? `${url.includes('?') ? '&' : '?'}defaultSchemaName=${enc(schemaName)}` : ''}`, { silent: true }),
    gcTime: 0,
    placeholderData: keepPreviousData,
  });

  const applySchema = (e: FormEvent) => {
    e.preventDefault();
    setSchemaName(withSchema ? schemaInput.trim() : '');
  };

  const copy = async () => {
    if (await copyText(data?.ddl ?? '')) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else {
      dialog.alert(t('복사하지 못했습니다. DDL 을 직접 선택해 복사해주세요.'));
    }
  };

  const warnings = data?.warnings ?? [];
  const pending = data?.pendingApprovals ?? [];
  return (
    <div className="popup-container table-ddl-popup">
      <div className="table-ddl-toolbar">
        <div className="table-ddl-meta">
          <span className="gh-label gh-label-accent">{data ? (data.dbTypeLabel ?? data.dbType ?? '-') : '-'}</span>
          <span className="gh-muted">{t('대상 DB 는 시스템 관리 > 프로젝트 관리에서 바꿀 수 있습니다.')}</span>
        </div>
        <span className="table-ddl-actions">
          {downloadName && <button type="button" className="btn btn-sm" disabled={!data?.ddl} onClick={() => data && downloadText(downloadName, data.ddl)}>{t('내보내기 (.sql)')}</button>}
          <button type="button" className="btn btn-sm" disabled={!data} onClick={copy}>{copied ? t('복사됨') : t('복사')}</button>
        </span>
      </div>
      <form className="table-ddl-schema" onSubmit={applySchema}>
        <label className="table-ddl-schema-check">
          <input type="checkbox" checked={withSchema}
            onChange={(e) => {
              setWithSchema(e.target.checked);
              if (!e.target.checked) setSchemaName('');
            }} />
          {t('스키마명 포함')}
        </label>
        <input type="text" className="form-control gh-mono" aria-label={t('기본 스키마에 붙일 스키마명')} placeholder={t('기본 스키마에 붙일 스키마명 (예: public)')}
          maxLength={63} disabled={!withSchema} value={schemaInput} onChange={(e) => setSchemaInput(e.target.value)} />
        <button type="submit" className="btn btn-sm" disabled={!withSchema || !schemaInput.trim()}>{t('적용')}</button>
      </form>
      {warnings.length > 0 && (
        <ul className="table-ddl-warnings">
          {warnings.map((w, i) => <li key={i}>{w}</li>)}
        </ul>
      )}
      {pending.length > 0 && (
        <div className="table-ddl-pending" role="note">
          <strong>{t('이 DDL 은 현재 설계 모델 기준이며 승인 대기 항목도 포함합니다.')}</strong>
          <ul>
            {pending.map((p, i) => <li key={i}>{p}</li>)}
          </ul>
        </div>
      )}
      <pre className="table-ddl-code">
        <code>{error ? (error instanceof ApiError ? error.message : t('DDL 을 생성하지 못했습니다.')) : data ? data.ddl : t('DDL 을 생성하고 있습니다.')}</code>
      </pre>
    </div>
  );
}

/** 클립보드 복사. http(비보안) 환경 등에서 Clipboard API 가 안 되면 textarea 선택 복사로 다시 시도한다 (통합 DDL 내보내기도 쓴다) */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 아래 방식으로 다시 시도
    }
  }
  const temp = document.createElement('textarea');
  temp.value = text;
  temp.style.position = 'fixed';
  temp.style.opacity = '0';
  (document.querySelector('dialog[open]:last-of-type') ?? document.body).appendChild(temp);
  temp.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  temp.remove();
  return ok;
}

/** 문자열을 파일로 저장한다 (브라우저 다운로드, 서버를 거치지 않는다) */
export function downloadText(fileName: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/sql;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
