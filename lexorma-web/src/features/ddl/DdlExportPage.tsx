import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError } from '../../lib/api';
import { t } from '../../lib/i18n';
import { useSchemas } from '../schema/schemas';
import { copyText, downloadText } from '../table/TableDdl';
import '../../styles/ddl_export.css';

/** GET /api/physical/ddl/export (DdlExportDto) : errors 가 있으면 ddl 은 비어 있다 */
interface DdlExport {
  dbType: string;
  dbTypeLabel: string;
  scope: 'PROJECT' | 'SCHEMA';
  schemaId: number | null;
  schemaName: string | null;
  approvedOnly: boolean;
  ddl: string;
  counts: { schemas: number; tables: number; indexes: number; commentedIndexes: number; foreignKeys: number; views: number };
  warnings: string[];
  errors: string[];
  pendingApprovals: string[];
}

interface ExportRequest {
  schemaId: string;
  dbType: string;
  schemaName: string;
  approvedOnly: boolean;
}

const DB_TYPES = [
  { value: 'POSTGRESQL', label: 'PostgreSQL' },
  { value: 'ORACLE', label: 'Oracle' },
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'MARIADB', label: 'MariaDB' },
];

/** 파일명에 쓸 수 있는 글자만 (영문·숫자·_·-). 남는 것이 없으면 대체값 */
function safeName(value: string | null | undefined, fallback: string) {
  const cleaned = (value ?? '').replace(/[^A-Za-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '');
  return cleaned || fallback;
}

/**
 * DDL 내보내기 (M-11) : 프로젝트 전체 또는 스키마 하나의 SCHEMA·TABLE·INDEX·FK·VIEW 를 한 스크립트로 만든다.
 * 서버가 만든 결과를 그대로 보여 주고, 복사·.sql 저장도 같은 결과를 쓴다. 어떤 DB 에서도 실행하지 않는다.
 */
export function DdlExportPage() {
  const { user } = useAuth();
  const dialog = useDialog();
  const { schemas } = useSchemas();
  const [form, setForm] = useState<ExportRequest>({ schemaId: '', dbType: '', schemaName: '', approvedOnly: false });
  const [withSchema, setWithSchema] = useState(false);
  const [request, setRequest] = useState<ExportRequest | null>(null);
  const [run, setRun] = useState(0); // 같은 조건으로 다시 눌러도 새로 만든다 (그 사이 모델이 바뀌었을 수 있다)
  const [copied, setCopied] = useState(false);

  const { data, error, isFetching } = useQuery({
    queryKey: ['ddl-export', user?.projectId, request, run],
    enabled: request != null,
    gcTime: 0,
    queryFn: () => {
      const r = request!;
      const params = new URLSearchParams();
      if (r.schemaId) params.set('schemaId', r.schemaId);
      if (r.dbType) params.set('dbType', r.dbType);
      if (r.schemaName) params.set('defaultSchemaName', r.schemaName);
      if (r.approvedOnly) params.set('approvedOnly', 'true');
      const query = params.toString();
      return api<DdlExport>(`/api/physical/ddl/export${query ? `?${query}` : ''}`, { silent: true });
    },
  });

  const generate = (e: FormEvent) => {
    e.preventDefault();
    setRequest({ ...form, schemaName: withSchema ? form.schemaName.trim() : '' });
    setRun((n) => n + 1);
  };

  const copy = async () => {
    if (!data?.ddl) return;
    if (await copyText(data.ddl)) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else {
      dialog.alert(t('복사하지 못했습니다. DDL 을 직접 선택해 복사해주세요.'));
    }
  };

  const fileName = data
    ? `lexorma-ddl-${safeName(user?.projectName, 'project')}${data.schemaName ? `-${safeName(data.schemaName, 'schema')}` : ''}-${data.dbType.toLowerCase()}.sql`
    : '';
  const countItems = data
    ? [
        ['SCHEMA', data.counts.schemas],
        ['TABLE', data.counts.tables],
        ['INDEX', data.counts.indexes],
        ['FK', data.counts.foreignKeys],
        ['VIEW', data.counts.views],
      ] as const
    : [];

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('DDL 내보내기')}
        description={t('프로젝트 전체 또는 스키마 하나의 테이블·인덱스·FK·VIEW 를 실행 순서대로 한 스크립트로 만듭니다. DDL 은 생성만 하며 어떤 DB 에서도 실행하지 않습니다.')} />

      <form className="ddl-export-options" onSubmit={generate} aria-label={t('내보내기 조건')}>
        <div className="form-group">
          <label htmlFor="ddlExportScope">{t('범위')}</label>
          <select id="ddlExportScope" className="form-control" value={form.schemaId} onChange={(e) => setForm({ ...form, schemaId: e.target.value })}>
            <option value="">{t('프로젝트 전체')}</option>
            {schemas.map((schema) => (
              <option key={schema.id} value={String(schema.id)}>
                {t('스키마')} : {schema.schemaName}{schema.isDefault ? ` (${t('기본')})` : ''}
              </option>
            ))}
          </select>
        </div>
        <div className="form-group">
          <label htmlFor="ddlExportDb">{t('대상 DBMS')}</label>
          <select id="ddlExportDb" className="form-control" value={form.dbType} onChange={(e) => setForm({ ...form, dbType: e.target.value })}>
            <option value="">{t('프로젝트 설정')}</option>
            {DB_TYPES.map((db) => <option key={db.value} value={db.value}>{db.label}</option>)}
          </select>
        </div>
        <div className="form-group ddl-export-schema">
          <label className="table-ddl-schema-check">
            <input type="checkbox" checked={withSchema} onChange={(e) => setWithSchema(e.target.checked)} />
            {t('스키마명 포함')}
          </label>
          <input type="text" className="form-control gh-mono" aria-label={t('기본 스키마에 붙일 스키마명')} placeholder={t('기본 스키마에 붙일 스키마명 (예: public)')}
            maxLength={63} disabled={!withSchema} value={form.schemaName} onChange={(e) => setForm({ ...form, schemaName: e.target.value })} />
        </div>
        <div className="form-group ddl-export-approved">
          <label className="table-ddl-schema-check">
            <input type="checkbox" checked={form.approvedOnly} onChange={(e) => setForm({ ...form, approvedOnly: e.target.checked })} />
            {t('승인 기준')}
          </label>
          <span className="gh-muted">{t('승인 대기 항목이 있으면 만들지 않습니다. 끄면 승인 대기 항목도 포함한 설계용 DDL 입니다.')}</span>
        </div>
        <div className="ddl-export-submit">
          <button type="submit" className="btn btn-primary" disabled={isFetching || (withSchema && !form.schemaName.trim())}>
            {isFetching ? t('생성 중…') : t('DDL 생성')}
          </button>
        </div>
      </form>

      {request && (
        <section className="ddl-export-result" aria-live="polite">
          <div className="table-ddl-toolbar ddl-export-toolbar">
            <div className="table-ddl-meta">
              {data && (
                <>
                  <span className="gh-label gh-label-accent">{data.dbTypeLabel}</span>
                  <span className="gh-label">{data.scope === 'PROJECT' ? t('프로젝트 전체') : `${t('스키마')} : ${data.schemaName}`}</span>
                  <span className="gh-label">{data.approvedOnly ? t('승인 기준') : t('설계용')}</span>
                  {countItems.map(([label, count]) => (
                    <span key={label} className="ddl-export-count"><span className="gh-muted">{label}</span> <strong>{count}</strong></span>
                  ))}
                  {data.counts.commentedIndexes > 0 && (
                    <span className="ddl-export-count" title={t('대상 DBMS 에서 만들 수 없어 주석으로 낸 인덱스')}>
                      <span className="gh-muted">{t('주석 처리 INDEX')}</span> <strong>{data.counts.commentedIndexes}</strong>
                    </span>
                  )}
                </>
              )}
            </div>
            <span className="table-ddl-actions">
              <button type="button" className="btn btn-sm" disabled={!data?.ddl} onClick={() => data && downloadText(fileName, data.ddl)}>{t('내보내기 (.sql)')}</button>
              <button type="button" className="btn btn-sm" disabled={!data?.ddl} onClick={copy}>{copied ? t('복사됨') : t('복사')}</button>
            </span>
          </div>

          {data && data.errors.length > 0 && (
            <div className="ddl-export-errors" role="alert">
              <strong>{t('DDL 을 만들 수 없습니다.')}</strong>
              <ul>{data.errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
            </div>
          )}
          {data && data.warnings.length > 0 && (
            <ul className="table-ddl-warnings">
              {data.warnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          )}
          {data && data.pendingApprovals.length > 0 && (
            <div className="table-ddl-pending" role="note">
              <strong>{data.approvedOnly ? t('승인 대기 항목') : t('이 DDL 은 현재 설계 모델 기준이며 승인 대기 항목도 포함합니다.')}</strong>
              <ul>{data.pendingApprovals.map((p, i) => <li key={i}>{p}</li>)}</ul>
            </div>
          )}
          <pre className="table-ddl-code ddl-export-code">
            <code>
              {error
                ? (error instanceof ApiError ? error.message : t('DDL 을 생성하지 못했습니다.'))
                : !data ? t('DDL 을 생성하고 있습니다.')
                : data.ddl || t('만들어진 DDL 이 없습니다.')}
            </code>
          </pre>
          {data?.ddl && <p className="gh-muted ddl-export-file">{t('파일명')} : <span className="gh-mono">{fileName}</span></p>}
        </section>
      )}
    </>
  );
}
