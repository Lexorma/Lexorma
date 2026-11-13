import { useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import {
  COLLECTABLE, DB_TYPES, dbTypeLabel, OPTIONS, PASSWORD_STATUS,
  type ConnectionTestResult, type DbType, type SourceConnection, type SourceConnectionRequest,
} from './model';
import { CONNECTIONS_KEY, useCanManageSource, useSourceConnections } from './shared';
import '../../styles/source.css';

/**
 * 원본 DB 연결 관리 (M-4a) : SourceConnectionRestController 의 목록·등록·수정·삭제·연결 테스트.
 * 접속 정보는 항목별로 받고 JDBC URL 은 서버가 만든다. 비밀번호는 보내기만 하고 응답에는 저장 여부만 있다.
 * 연결 테스트·수집은 PostgreSQL 만 된다 (다른 DBMS 는 연결 설정만 저장).
 */
export function SourceConnectionPage() {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const canManage = useCanManageSource();
  const connections = useSourceConnections();
  const reload = () => queryClient.invalidateQueries({ queryKey: [CONNECTIONS_KEY] });

  const openForm = (connection?: SourceConnection) => {
    dialog.open({
      title: connection ? t('연결 수정 · {0}', connection.name) : t('연결 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ConnectionForm connection={connection} onCancel={close} onSaved={() => { close(); void reload(); }} />,
    });
  };

  const test = (connection: SourceConnection) => {
    dialog.open({
      title: t('연결 테스트 · {0}', connection.name),
      content: () => <SavedConnectionTest connection={connection} />,
    });
  };

  const remove = (connection: SourceConnection) => {
    dialog.confirm(t('{0} 연결을 삭제하시겠습니까? 이 연결로 수집한 원본 카탈로그·수집 이력·변경 이력도 함께 삭제됩니다. Lexorma 물리·논리 모델은 삭제하지 않습니다.', connection.name), async () => {
      const result = await api<{ deletedCatalogRows: number }>(`/api/source/connections/${enc(connection.id)}`, { method: 'DELETE' });
      await reload();
      dialog.alert(t('연결을 삭제했습니다. (원본 카탈로그 {0}건 삭제)', result.deletedCatalogRows));
    });
  };

  const list = connections.data ?? [];
  return (
    <>
      <PageHeader category={t('원본 DB 관리')} title={t('연결 관리')}
        description={t('메타데이터를 수집할 원본(외부) DB 의 접속 정보를 관리합니다. Lexorma 는 원본 DB 를 조회만 하며 데이터나 구조를 바꾸지 않습니다. 비밀번호는 암호화해 저장하고 화면에 다시 보여 주지 않습니다.')} />

      <div className="gh-flash source-flash" role="note">
        <span>{t('연결 테스트와 메타데이터 수집은 PostgreSQL 만 지원합니다. Oracle · MySQL · MariaDB 는 연결 설정만 저장할 수 있습니다.')}</span>
      </div>
      {!canManage && (
        <p className="gh-muted source-readonly">{t('연결 등록·수정·삭제·테스트는 이 프로젝트의 관리자 또는 ADMIN 만 할 수 있습니다. 목록은 조회만 할 수 있습니다.')}</p>
      )}

      <div className="source-toolbar">
        <span className="gh-muted">{t('연결 {0}개', list.length)}</span>
        {canManage && <button type="button" className="btn btn-primary" onClick={() => openForm()}>{t('연결 등록')}</button>}
      </div>

      {connections.isError ? <p role="alert">{t('연결 목록을 불러오지 못했습니다.')} {connections.error.message}</p>
        : connections.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : list.length === 0 ? (
          <div className="source-empty" role="status">
            <p>{t('등록된 원본 DB 연결이 없습니다.')}</p>
            {canManage && <p className="gh-muted">{t('[연결 등록] 으로 첫 연결을 등록하세요.')}</p>}
          </div>
        ) : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead>
                <tr>
                  <th>{t('연결명')}</th>
                  <th>{t('DBMS')}</th>
                  <th>{t('접속 대상')}</th>
                  <th>{t('계정')}</th>
                  <th>{t('상태')}</th>
                  <th className="text-end">{t('수집한 스키마')}</th>
                  <th>{t('작업')}</th>
                </tr>
              </thead>
              <tbody>
                {list.map((c) => {
                  const password = PASSWORD_STATUS[c.passwordStatus] ?? PASSWORD_STATUS.NONE;
                  const collectable = COLLECTABLE.has(c.dbType);
                  return (
                    <tr key={c.id}>
                      <td>
                        <strong>{c.name}</strong>
                        {c.description && <div className="gh-muted source-sub">{c.description}</div>}
                      </td>
                      <td>
                        {dbTypeLabel(c.dbType)}
                        <div>{collectable ? <Label variant="success">{t('수집 지원')}</Label> : <Label variant="attention" title={t('연결 설정만 저장할 수 있습니다.')}>{t('설정만 저장')}</Label>}</div>
                      </td>
                      <td className="gh-mono source-break">
                        {c.host}:{c.port}/{c.databaseName}
                        {c.dbType === 'ORACLE' && <div className="gh-muted source-sub">{c.databaseNameType === 'SID' ? 'SID' : t('서비스명')}</div>}
                      </td>
                      <td className="gh-mono source-break">{c.username}</td>
                      <td>
                        <div className="source-labels">
                          <Label variant={c.enabled ? 'success' : ''} dot>{c.enabled ? t('사용') : t('사용 중지')}</Label>
                          <Label variant={password.variant}>{password.label}</Label>
                          {!c.driverAvailable && <Label variant="danger">{t('드라이버 없음')}</Label>}
                        </div>
                      </td>
                      <td className="text-end">
                        {c.schemaCount > 0 ? <Link to={`/sourceCatalog?connectionId=${c.id}`}>{c.schemaCount}</Link> : <span className="gh-muted">0</span>}
                      </td>
                      <td>
                        <div className="source-actions">
                          <Link className="btn btn-sm" to={`/sourceCollection?connectionId=${c.id}`}>{t('수집')}</Link>
                          {canManage && (
                            <>
                              <button type="button" className="btn btn-sm" disabled={!collectable} title={collectable ? undefined : t('이 DB 유형은 아직 연결 테스트를 지원하지 않습니다.')} onClick={() => test(c)}>{t('테스트')}</button>
                              <button type="button" className="btn btn-sm" onClick={() => openForm(c)}>{t('수정')}</button>
                              <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(c)}>{t('삭제')}</button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
    </>
  );
}

/** 저장된 연결로 바로 테스트한다 (열면 한 번 실행, 다시 실행 가능) */
function SavedConnectionTest({ connection }: { connection: SourceConnection }) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setResult(await api<ConnectionTestResult>(`/api/source/connections/${enc(connection.id)}/test`, { method: 'POST', silent: true }));
    } catch (e) {
      setResult(null);
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  };
  return (
    <div className="source-test">
      <p className="gh-muted">{t('저장된 설정으로 원본 DB 에 접속해 메타데이터를 읽을 수 있는지 확인합니다. 원본 DB 를 바꾸지 않습니다.')}</p>
      <button type="button" className="btn btn-primary" disabled={running} onClick={() => void run()}>{running ? t('테스트 중…') : t('연결 테스트')}</button>
      {error && <p role="alert" className="source-error">{error}</p>}
      {result && <TestResultView result={result} />}
    </div>
  );
}

/** 연결 테스트 결과 : 실패는 200 의 errorCode·message 로 온다 (비밀번호·JDBC URL·드라이버 메시지는 서버가 넣지 않는다) */
export function TestResultView({ result }: { result: ConnectionTestResult }) {
  const ok = result.connected && result.metadataReadable;
  const tls = result.tls;
  return (
    <div className={`source-test-result ${ok ? 'is-ok' : 'is-fail'}`} role="status" aria-live="polite">
      <div className="source-labels">
        <Label variant={ok ? 'success' : 'danger'} dot>{ok ? t('연결 성공') : t('연결 실패')}</Label>
        <Label variant={result.connected ? 'success' : 'danger'}>{result.connected ? t('접속됨') : t('접속 안 됨')}</Label>
        {result.connected && <Label variant={result.metadataReadable ? 'success' : 'danger'}>{result.metadataReadable ? t('메타데이터 조회 가능') : t('메타데이터 조회 불가')}</Label>}
      </div>
      {!ok && result.message && <p className="source-error">{result.message}{result.errorCode && <span className="gh-mono gh-muted"> ({result.errorCode})</span>}</p>}
      {(result.productName || tls) && (
        <dl className="source-dl">
          {result.productName && <><dt>{t('DB 제품')}</dt><dd>{result.productName} {result.productVersion ?? ''}</dd></>}
          {tls && (
            <>
              <dt>{t('전송 암호화')}</dt>
              <dd>
                {tls.encrypted ? t('암호화됨') : t('암호화 안 됨')}{tls.protocol ? ` · ${tls.protocol}` : ''}{tls.mode ? ` · sslmode=${tls.mode}` : ''}
                {tls.encrypted && <span className="gh-muted"> · {tls.certificateVerified ? t('인증서 확인함') : t('인증서 확인 안 함')} · {tls.hostnameVerified ? t('호스트명 확인함') : t('호스트명 확인 안 함')}</span>}
              </dd>
            </>
          )}
        </dl>
      )}
    </div>
  );
}

/** 연결 등록·수정 : 항목 라벨 위 입력, 아래 [연결 테스트] [닫기] [저장]. 수정할 때 비밀번호를 비우면 저장된 것을 그대로 쓴다 */
function ConnectionForm({ connection, onSaved, onCancel }: {
  connection?: SourceConnection;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [dbType, setDbType] = useState<DbType>(connection?.dbType ?? 'POSTGRESQL');
  const [name, setName] = useState(connection?.name ?? '');
  const [host, setHost] = useState(connection?.host ?? '');
  const [port, setPort] = useState(connection ? String(connection.port) : '');
  const [databaseName, setDatabaseName] = useState(connection?.databaseName ?? '');
  const [nameType, setNameType] = useState(connection?.dbType === 'ORACLE' ? connection.databaseNameType : 'SERVICE_NAME');
  const [username, setUsername] = useState(connection?.username ?? '');
  const [password, setPassword] = useState('');
  const [option, setOption] = useState(() => {
    const key = connection ? OPTIONS[connection.dbType]?.key : undefined;
    return (key && connection?.options?.[key]) || '';
  });
  const [comparison, setComparison] = useState(connection?.identifierComparison ?? 'DBMS_DEFAULT');
  const [description, setDescription] = useState(connection?.description ?? '');
  const [enabled, setEnabled] = useState(connection?.enabled ?? true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const optionDef = OPTIONS[dbType];
  const collectable = COLLECTABLE.has(dbType);
  const defaultPort = DB_TYPES.find((d) => d.value === dbType)?.defaultPort;
  const mysqlFamily = dbType === 'MYSQL' || dbType === 'MARIADB';

  const changeDbType = (next: DbType) => {
    setDbType(next);
    setOption('');
    if (next !== 'MYSQL' && next !== 'MARIADB') setComparison('DBMS_DEFAULT');
    setTestResult(null);
  };

  const request = (): SourceConnectionRequest => ({
    name: name.trim(),
    dbType,
    host: host.trim(),
    port: port.trim() ? Number(port) : null,
    databaseName: databaseName.trim(),
    databaseNameType: dbType === 'ORACLE' ? nameType : 'DATABASE',
    username: username.trim(),
    password: password ? password : null,
    options: optionDef && option ? { [optionDef.key]: option } : {},
    identifierComparison: mysqlFamily ? comparison : 'DBMS_DEFAULT',
    description: description.trim() || null,
    enabled,
  });

  const runTest = async () => {
    setTesting(true);
    setError(null);
    setTestResult(null);
    try {
      const query = connection ? `?connectionId=${enc(connection.id)}` : '';
      setTestResult(await api<ConnectionTestResult>(`/api/source/connections/test${query}`, { method: 'POST', body: request(), silent: true }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(connection ? `/api/source/connections/${enc(connection.id)}` : '/api/source/connections', { method: connection ? 'PUT' : 'POST', body: request(), silent: true });
      onSaved();
    } catch (e2) {
      setError((e2 as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="source-form" onSubmit={(e) => void submit(e)} autoComplete="off">
      {!collectable && (
        <div className="gh-flash gh-flash-attention" role="note">
          <span>{t('{0} 은(는) 아직 연결 테스트와 메타데이터 수집을 지원하지 않습니다. 연결 설정만 저장됩니다.', dbTypeLabel(dbType))}</span>
        </div>
      )}
      <div className="source-form-grid">
        <div className="form-group">
          <label htmlFor="srcName">{t('연결명')}</label>
          <input id="srcName" type="text" className="form-control" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="srcDbType">{t('DBMS')}</label>
          <select id="srcDbType" className="form-control" value={dbType} onChange={(e) => changeDbType(e.target.value as DbType)}>
            {DB_TYPES.map((d) => <option key={d.value} value={d.value}>{d.label}{COLLECTABLE.has(d.value) ? '' : ` — ${t('설정만 저장')}`}</option>)}
          </select>
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="srcHost">{t('호스트')}</label>
          <input id="srcHost" type="text" className="form-control gh-mono" required maxLength={255} placeholder={t('예: db.example.com')} value={host} onChange={(e) => setHost(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="srcPort">{t('포트')}</label>
          <input id="srcPort" type="number" className="form-control gh-mono" min={1} max={65535} placeholder={defaultPort ? t('기본 {0}', defaultPort) : undefined} value={port} onChange={(e) => setPort(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="srcDatabase">{dbType === 'ORACLE' ? t('서비스명 또는 SID') : t('데이터베이스명')}</label>
          <input id="srcDatabase" type="text" className="form-control gh-mono" required maxLength={255} value={databaseName} onChange={(e) => setDatabaseName(e.target.value)} />
        </div>
        {dbType === 'ORACLE' && (
          <div className="form-group">
            <label htmlFor="srcNameType">{t('이름 종류')}</label>
            <select id="srcNameType" className="form-control" value={nameType} onChange={(e) => setNameType(e.target.value as SourceConnection['databaseNameType'])}>
              <option value="SERVICE_NAME">{t('서비스명')}</option>
              <option value="SID">SID</option>
            </select>
          </div>
        )}
        <div className="form-group">
          <label htmlFor="srcUsername">{t('DB 사용자명')}</label>
          <input id="srcUsername" type="text" className="form-control gh-mono" required maxLength={255} autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="srcPassword">{t('비밀번호')}</label>
          <input id="srcPassword" type="password" className="form-control" maxLength={1000} autoComplete="new-password"
            placeholder={connection?.passwordConfigured ? t('변경하지 않으려면 비워 두세요') : undefined} value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        {optionDef && (
          <div className="form-group">
            <label htmlFor="srcOption">{optionDef.key}</label>
            <select id="srcOption" className="form-control gh-mono" value={option} onChange={(e) => setOption(e.target.value)}>
              <option value="">{t('지정 안 함 (드라이버 기본값)')}</option>
              {optionDef.values.map((v) => <option key={v} value={v}>{v}</option>)}
            </select>
          </div>
        )}
        {mysqlFamily && (
          <div className="form-group">
            <label htmlFor="srcComparison">{t('식별자 대소문자')}</label>
            <select id="srcComparison" className="form-control" value={comparison} onChange={(e) => setComparison(e.target.value as SourceConnection['identifierComparison'])}>
              <option value="DBMS_DEFAULT">{t('모름 (구분한다)')}</option>
              <option value="CASE_SENSITIVE">{t('구분한다 (lower_case_table_names=0)')}</option>
              <option value="CASE_INSENSITIVE">{t('구분하지 않는다 (lower_case_table_names=1·2)')}</option>
            </select>
          </div>
        )}
        <div className="form-group source-span-2">
          <label htmlFor="srcDescription">{t('설명')}</label>
          <input id="srcDescription" type="text" className="form-control" maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="form-group source-span-2 source-check">
          <label><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {t('사용 (끄면 수집할 수 없습니다)')}</label>
        </div>
      </div>
      <p className="form-help">{t('JDBC URL 은 입력하지 않습니다. 서버가 위 항목으로 만들고, 운영자가 허용한 주소로만 접속합니다.')}</p>

      {error && <p role="alert" className="source-error">{error}</p>}
      {testResult && <TestResultView result={testResult} />}

      <div className="gh-dialog-actions">
        <button type="button" className="btn" disabled={!collectable || testing} title={collectable ? undefined : t('이 DB 유형은 아직 연결 테스트를 지원하지 않습니다.')} onClick={() => void runTest()}>
          {testing ? t('테스트 중…') : t('연결 테스트')}
        </button>
        <span className="source-grow" />
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? t('저장 중…') : connection ? t('저장') : t('등록')}</button>
      </div>
    </form>
  );
}
