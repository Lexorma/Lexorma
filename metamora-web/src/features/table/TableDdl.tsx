import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api, ApiError, enc } from '../../lib/api';

interface TableDdl {
  dbType: string;
  dbTypeLabel?: string;
  ddl: string;
  warnings?: string[];
}

/** 테이블 DDL : 프로젝트 대상 DB(시스템 관리 > 프로젝트 관리) 기준으로 서버에서 생성한 DDL */
export function TableDdlView({ tableId }: { tableId: number }) {
  const dialog = useDialog();
  const [copied, setCopied] = useState(false);
  const { data, error } = useQuery({
    queryKey: ['table-ddl', tableId],
    queryFn: () => api<TableDdl>(`/api/selectTable/ddl/${enc(tableId)}`, { silent: true }),
    gcTime: 0,
  });

  const copy = async () => {
    const text = data?.ddl ?? '';
    const done = () => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    };
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return done();
      } catch {
        // http(비보안) 환경 등 : 아래 방식으로 다시 시도
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
    if (ok) done();
    else dialog.alert('복사하지 못했습니다. DDL 을 직접 선택해 복사해주세요.');
  };

  const warnings = data?.warnings ?? [];
  return (
    <div className="popup-container table-ddl-popup">
      <div className="table-ddl-toolbar">
        <div className="table-ddl-meta">
          <span className="gh-label gh-label-accent">{data ? (data.dbTypeLabel ?? data.dbType ?? '-') : '-'}</span>
          <span className="gh-muted">대상 DB 는 시스템 관리 &gt; 프로젝트 관리에서 바꿀 수 있습니다.</span>
        </div>
        <button type="button" className="btn btn-sm" disabled={!data} onClick={copy}>{copied ? '복사됨' : '복사'}</button>
      </div>
      {warnings.length > 0 && (
        <ul className="table-ddl-warnings">
          {warnings.map((w, i) => <li key={i}>{w}</li>)}
        </ul>
      )}
      <pre className="table-ddl-code">
        <code>{error ? (error instanceof ApiError ? error.message : 'DDL 을 생성하지 못했습니다.') : data ? data.ddl : 'DDL 을 생성하고 있습니다.'}</code>
      </pre>
    </div>
  );
}
