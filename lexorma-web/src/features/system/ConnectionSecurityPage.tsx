import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { History, ListChecks, Plus, ShieldCheck, TriangleAlert } from 'lucide-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Pager } from '../source/shared';
import '../../styles/source.css';

/**
 * 외부 연결 보안 관리 (SEC-1) : 시스템 관리자(ADMIN) 전용. 연결 유형(JDBC·AI HTTP)별 허용 규칙(호스트·CIDR·포트), 허용 규칙이 없는 등록 연결, 연결 시도 감사, 정책 변경 이력.
 * 규칙이 없으면 연결하지 않는다 (기본 차단). 절대 차단 주소(링크 로컬·클라우드 메타데이터 등)는 규칙으로도 열리지 않는다. 판정·검증은 모두 서버가 한다.
 */
type ConnectionType = 'JDBC' | 'AI_HTTP';
type Reason = 'NO_RULE' | 'PORT_NOT_ALLOWED' | 'HOST_NOT_ALLOWED' | 'ADDRESS_NOT_ALLOWED' | 'MIXED_ADDRESSES' | 'BLOCKED_HOST' | 'BLOCKED_ADDRESS' | 'INVALID_HOST'
  | 'DNS_FAILED' | 'OPERATOR_LIMIT' | 'POLICY_ERROR' | 'TARGET_CHANGED' | 'CONNECT_FAILED';
type Result = 'ALLOWED' | 'DENIED' | 'CONNECTED' | 'FAILED' | 'BLOCKED';
type ChangeType = 'CREATE' | 'UPDATE' | 'DELETE' | 'ENABLE' | 'DISABLE';

interface Rule {
  id: number; connectionType: ConnectionType; host: string | null; cidr: string; port: number; enabled: boolean; description: string | null;
  createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null;
}
interface RuleRequest { connectionType: ConnectionType; host: string | null; cidr: string; port: number | null; enabled: boolean; description: string | null }
interface Attempt {
  id: number; connectionType: ConnectionType; host: string; port: number; addresses: string | null; ruleId: number | null; result: Result; reason: Reason | null;
  executionType: string | null; requestId: string | null; userId: string | null; attemptedAt: string; updatedAt: string | null;
}
interface RuleChange { id: number; ruleId: number; changeType: ChangeType; before: string | null; after: string | null; requestId: string | null; userId: string | null; changedAt: string }
interface Unmatched { connectionType: ConnectionType; host: string; port: number; label: string; reason: Reason }
interface Settings { blockedNetworks: string[]; blockedHosts: string[] }
interface Page<T> { rows: T[]; total: number }

const KEY = 'connection-security';
const TYPE_LABEL: Record<ConnectionType, string> = { JDBC: t('JDBC (원본 DB)'), AI_HTTP: t('AI HTTP') };
const REASON: Record<Reason, string> = {
  NO_RULE: t('관리자가 허용한 규칙이 없습니다'),
  PORT_NOT_ALLOWED: t('허용하지 않은 포트입니다'),
  HOST_NOT_ALLOWED: t('허용하지 않은 호스트입니다'),
  ADDRESS_NOT_ALLOWED: t('허용 대역 밖의 주소입니다'),
  MIXED_ADDRESSES: t('호스트가 여러 주소로 해석되었지만 그 주소를 모두 담는 허용 규칙이 없습니다. localhost 처럼 IPv4·IPv6 로 함께 해석되는 이름이면 127.0.0.1 또는 ::1 같은 명시적인 IP 주소를 사용하세요.'),
  BLOCKED_HOST: t('클라우드 메타데이터 이름은 항상 차단됩니다'),
  BLOCKED_ADDRESS: t('항상 차단되는 주소입니다 (링크 로컬·메타데이터·멀티캐스트·미지정 등)'),
  INVALID_HOST: t('호스트 또는 포트 형식이 올바르지 않습니다'),
  DNS_FAILED: t('호스트 이름을 풀지 못했습니다'),
  OPERATOR_LIMIT: t('운영자가 정한 범위 밖의 사설·루프백 주소입니다'),
  POLICY_ERROR: t('정책을 확인하지 못했습니다'),
  TARGET_CHANGED: t('연결 시점의 목적지가 확인한 주소와 다릅니다'),
  CONNECT_FAILED: t('연결하지 못했습니다'),
};
// 감사 행의 주소에 IPv4·IPv6 가 함께 있으면 (localhost 이중 해석) 그 원인을 안내한다 — 서버 오류 문구와 같다
function reasonLabel(reason: Reason, addresses?: string | null): string {
  const list = (addresses ?? '').split(',').map((a) => a.trim()).filter(Boolean);
  if (reason === 'MIXED_ADDRESSES' && list.some((a) => a.includes(':')) && list.some((a) => !a.includes(':'))) {
    return t('호스트가 IPv4와 IPv6 주소로 동시에 해석되어 현재 허용 규칙과 일치하지 않습니다. localhost 대신 127.0.0.1 또는 ::1과 같은 명시적인 IP 주소를 사용하세요.');
  }
  return REASON[reason];
}
const RESULT: Record<Result, { label: string; variant: LabelVariant }> = {
  ALLOWED: { label: t('허용'), variant: 'accent' },
  CONNECTED: { label: t('연결됨'), variant: 'success' },
  FAILED: { label: t('연결 실패'), variant: 'attention' },
  DENIED: { label: t('차단'), variant: 'danger' },
  BLOCKED: { label: t('연결 시점 차단'), variant: 'danger' },
};
const CHANGE: Record<ChangeType, string> = { CREATE: t('등록'), UPDATE: t('수정'), DELETE: t('삭제'), ENABLE: t('활성화'), DISABLE: t('비활성화') };

function hostText(host: string | null) {
  return host ?? t('(모든 호스트)');
}

export function ConnectionSecurityPage() {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [type, setType] = useState<ConnectionType | ''>('');
  const [keyword, setKeyword] = useState('');
  const reload = () => queryClient.invalidateQueries({ queryKey: [KEY] });
  const params = new URLSearchParams();
  if (type) params.set('type', type);
  if (keyword.trim()) params.set('keyword', keyword.trim());
  const rules = useQuery({ queryKey: [KEY, 'rules', type, keyword.trim()], queryFn: () => api<Rule[]>(`/api/security/connection-policies?${params}`) });
  const settings = useQuery({ queryKey: [KEY, 'settings'], queryFn: () => api<Settings>('/api/security/connection-policies/settings') });

  const openForm = (rule?: Rule, preset?: Partial<RuleRequest>) => {
    dialog.open({
      title: rule ? t('허용 규칙 수정 · #{0}', rule.id) : t('허용 규칙 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <RuleForm rule={rule} preset={preset} settings={settings.data} onCancel={close} onSaved={() => { close(); void reload(); }} />,
    });
  };
  const toggle = async (rule: Rule) => {
    await api(`/api/security/connection-policies/${enc(rule.id)}`, {
      method: 'PUT', body: { connectionType: rule.connectionType, host: rule.host, cidr: rule.cidr, port: rule.port, enabled: !rule.enabled, description: rule.description },
    });
    await reload();
  };
  const remove = (rule: Rule) => {
    dialog.confirm(t('#{0} 규칙을 삭제하시겠습니까? 이 규칙으로만 열리던 연결은 다음 연결부터 막힙니다.', rule.id), async () => {
      await api(`/api/security/connection-policies/${enc(rule.id)}`, { method: 'DELETE' });
      await reload();
    });
  };

  const list = rules.data ?? [];
  return (
    <>
      <PageHeader category={t('시스템 관리')} title={t('외부 연결 보안')}
        description={t('Lexorma 가 원본 DB(JDBC)·AI 서버(HTTP)로 나가는 연결을 관리자가 허용한 목적지로만 제한합니다. 규칙이 없으면 연결하지 않습니다 (기본 차단).')} />

      <div className="gh-flash source-flash" role="note">
        <span>
          {t('한 규칙의 연결 유형·포트·호스트(정한 경우)·허용 대역이 모두 맞아야 연결합니다. 호스트 이름은 연결할 때 풀어 모든 주소가 대역 안이어야 하고, 실제 연결도 그 주소로만 합니다.')}
          {settings.data && <> {t('항상 차단 : {0}', settings.data.blockedNetworks.join(', '))}</>}
        </span>
      </div>

      <section className="source-card" aria-label={t('허용 규칙')}>
        <h3 className="source-title-icon"><ShieldCheck size={16} aria-hidden="true" /> {t('허용 규칙')}</h3>
        <div className="source-toolbar">
          <select className="form-control" aria-label={t('연결 유형')} value={type} onChange={(e) => setType(e.target.value as ConnectionType | '')}>
            <option value="">{t('전체 유형')}</option>
            <option value="JDBC">{TYPE_LABEL.JDBC}</option>
            <option value="AI_HTTP">{TYPE_LABEL.AI_HTTP}</option>
          </select>
          <input type="search" className="form-control" aria-label={t('호스트 검색')} placeholder={t('호스트·대역·설명 검색')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          <span className="source-grow" />
          <button type="button" className="btn btn-primary" onClick={() => openForm()}><Plus size={14} aria-hidden="true" /> {t('규칙 등록')}</button>
        </div>
        {rules.isError ? <p role="alert">{rules.error.message}</p>
          : rules.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
          : list.length === 0 ? <p className="gh-muted" role="status">{t('허용 규칙이 없습니다. 지금은 모든 외부 연결이 차단됩니다.')}</p>
          : (
            <div className="source-table-wrap">
              <table className="source-table">
                <thead>
                  <tr><th>#</th><th>{t('연결 유형')}</th><th>{t('호스트')}</th><th>{t('허용 대역')}</th><th className="text-end">{t('포트')}</th><th>{t('상태')}</th><th>{t('설명')}</th><th>{t('수정')}</th><th>{t('작업')}</th></tr>
                </thead>
                <tbody>
                  {list.map((r) => (
                    <tr key={r.id}>
                      <td>{r.id}</td>
                      <td>{TYPE_LABEL[r.connectionType]}</td>
                      <td className="gh-mono source-break">{hostText(r.host)}</td>
                      <td className="gh-mono source-break">{r.cidr}</td>
                      <td className="gh-mono text-end">{r.port}</td>
                      <td><Label variant={r.enabled ? 'success' : ''} dot>{r.enabled ? t('활성') : t('비활성')}</Label></td>
                      <td className="source-break">{r.description ?? <span className="gh-muted">-</span>}</td>
                      <td className="source-sub">
                        <div>{r.updatedBy ?? r.createdBy ?? '-'}</div>
                        <div className="gh-muted">{formatDateTime(r.updatedAt ?? r.createdAt ?? '')}</div>
                      </td>
                      <td>
                        <div className="source-actions">
                          <button type="button" className="btn btn-sm" onClick={() => void toggle(r)}>{r.enabled ? t('비활성화') : t('활성화')}</button>
                          <button type="button" className="btn btn-sm" onClick={() => openForm(r)}>{t('수정')}</button>
                          <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(r)}>{t('삭제')}</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </section>

      <UnmatchedTargets onRegister={(u) => openForm(undefined, { connectionType: u.connectionType, host: u.host, port: u.port })} />
      <ConnectionAudits />
      <PolicyChanges />
    </>
  );
}

/** 등록된 연결 중 지금 규칙으로 열리지 않는 것 : 이름을 풀어 판정하므로 눌렀을 때만 확인한다 */
function UnmatchedTargets({ onRegister }: { onRegister: (target: Unmatched) => void }) {
  const [enabled, setEnabled] = useState(false);
  const unmatched = useQuery({ queryKey: [KEY, 'unmatched'], queryFn: () => api<Unmatched[]>('/api/security/connection-policies/unmatched'), enabled });
  return (
    <section className="source-card" aria-label={t('허용 규칙이 없는 등록 연결')}>
      <h3 className="source-title-icon"><TriangleAlert size={16} aria-hidden="true" /> {t('허용 규칙이 없는 등록 연결')}</h3>
      <p className="form-help">{t('기본 차단으로 바뀌면서 규칙을 자동으로 만들지 않습니다. 등록된 원본 DB 연결 중 지금 열리지 않는 목적지를 확인해 필요한 규칙만 등록하세요.')}</p>
      <div className="source-toolbar">
        <button type="button" className="btn" disabled={unmatched.isFetching} onClick={() => (enabled ? void unmatched.refetch() : setEnabled(true))}>
          <ListChecks size={14} aria-hidden="true" /> {unmatched.isFetching ? t('확인 중…') : t('지금 확인')}
        </button>
      </div>
      {unmatched.isError ? <p role="alert">{unmatched.error.message}</p>
        : !unmatched.data ? null
        : unmatched.data.length === 0 ? <p className="gh-muted" role="status">{t('모든 등록 연결이 허용 규칙으로 열립니다.')}</p>
        : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th>{t('연결')}</th><th>{t('연결 유형')}</th><th>{t('목적지')}</th><th>{t('사유')}</th><th>{t('작업')}</th></tr></thead>
              <tbody>
                {unmatched.data.map((u) => (
                  <tr key={`${u.connectionType}|${u.host}|${u.port}`}>
                    <td className="source-break">{u.label}</td>
                    <td>{TYPE_LABEL[u.connectionType]}</td>
                    <td className="gh-mono source-break">{u.host}:{u.port}</td>
                    <td className="gh-muted">{REASON[u.reason]}</td>
                    <td><button type="button" className="btn btn-sm" onClick={() => onRegister(u)}>{t('규칙 등록')}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </section>
  );
}

function ConnectionAudits() {
  const [page, setPage] = useState(0);
  const [type, setType] = useState('');
  const [result, setResult] = useState('');
  const [keyword, setKeyword] = useState('');
  const [user, setUser] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (type) params.set('type', type);
  if (result) params.set('result', result);
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (user.trim()) params.set('user', user.trim());
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  const attempts = useQuery({ queryKey: [KEY, 'attempts', params.toString()], queryFn: () => api<Page<Attempt>>(`/api/security/connection-audits?${params}`) });
  const reset = (apply: () => void) => {
    apply();
    setPage(0);
  };
  return (
    <section className="source-card" aria-label={t('연결 시도 감사')}>
      <h3 className="source-title-icon"><History size={16} aria-hidden="true" /> {t('연결 시도 감사')}</h3>
      <div className="source-toolbar source-wrap">
        <select className="form-control" aria-label={t('연결 유형')} value={type} onChange={(e) => reset(() => setType(e.target.value))}>
          <option value="">{t('전체 유형')}</option>
          <option value="JDBC">{TYPE_LABEL.JDBC}</option>
          <option value="AI_HTTP">{TYPE_LABEL.AI_HTTP}</option>
        </select>
        <select className="form-control" aria-label={t('결과')} value={result} onChange={(e) => reset(() => setResult(e.target.value))}>
          <option value="">{t('전체 결과')}</option>
          <option value="ALLOWED">{t('허용')}</option>
          <option value="DENIED">{t('차단')}</option>
        </select>
        <input type="search" className="form-control" aria-label={t('목적지 검색')} placeholder={t('목적지 호스트')} value={keyword} onChange={(e) => reset(() => setKeyword(e.target.value))} />
        <input type="search" className="form-control" aria-label={t('수행자')} placeholder={t('수행자')} value={user} onChange={(e) => reset(() => setUser(e.target.value))} />
        <input type="date" className="form-control" aria-label={t('시작일')} value={from} onChange={(e) => reset(() => setFrom(e.target.value))} />
        <input type="date" className="form-control" aria-label={t('종료일')} value={to} onChange={(e) => reset(() => setTo(e.target.value))} />
      </div>
      {attempts.isError ? <p role="alert">{attempts.error.message}</p>
        : !attempts.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : attempts.data.rows.length === 0 ? <p className="gh-muted" role="status">{t('연결 시도 기록이 없습니다.')}</p>
        : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('시각')}</th><th>{t('연결 유형')}</th><th>{t('목적지')}</th><th>{t('검증한 주소')}</th><th>{t('결과')}</th><th>{t('수행자')}</th></tr></thead>
                <tbody>
                  {attempts.data.rows.map((a) => (
                    <tr key={a.id}>
                      <td className="source-sub">{formatDateTime(a.attemptedAt)}</td>
                      <td>{TYPE_LABEL[a.connectionType]}</td>
                      <td className="gh-mono source-break">{a.host}:{a.port}{a.ruleId != null && <div className="gh-muted source-sub">{t('규칙 #{0}', a.ruleId)}</div>}</td>
                      <td className="gh-mono source-break source-sub">{a.addresses ?? '-'}</td>
                      <td>
                        <Label variant={RESULT[a.result].variant}>{RESULT[a.result].label}</Label>
                        {a.reason && <div className="gh-muted source-sub">{reasonLabel(a.reason, a.addresses)}</div>}
                      </td>
                      <td className="source-sub">{a.userId ?? '-'}{a.executionType && <div className="gh-muted">{a.executionType}</div>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} size={20} total={attempts.data.total} onPage={setPage} />
          </>
        )}
    </section>
  );
}

function PolicyChanges() {
  const [page, setPage] = useState(0);
  const changes = useQuery({ queryKey: [KEY, 'changes', page], queryFn: () => api<Page<RuleChange>>(`/api/security/policy-audits?page=${page}&size=20`) });
  const summary = (json: string | null) => {
    if (!json) return '-';
    try {
      const v = JSON.parse(json) as Record<string, unknown>;
      return `${v.connectionType} ${v.host ?? '*'} ${v.cidr} :${v.port} ${v.enabled ? 'ON' : 'OFF'}`;
    } catch {
      return json;
    }
  };
  return (
    <section className="source-card" aria-label={t('정책 변경 이력')}>
      <h3 className="source-title-icon"><History size={16} aria-hidden="true" /> {t('정책 변경 이력')}</h3>
      {changes.isError ? <p role="alert">{changes.error.message}</p>
        : !changes.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : changes.data.rows.length === 0 ? <p className="gh-muted" role="status">{t('정책 변경 이력이 없습니다.')}</p>
        : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('시각')}</th><th>{t('작업')}</th><th>{t('규칙')}</th><th>{t('변경 전')}</th><th>{t('변경 후')}</th><th>{t('수행자')}</th></tr></thead>
                <tbody>
                  {changes.data.rows.map((c) => (
                    <tr key={c.id}>
                      <td className="source-sub">{formatDateTime(c.changedAt)}</td>
                      <td><Label>{CHANGE[c.changeType]}</Label></td>
                      <td>#{c.ruleId}</td>
                      <td className="gh-mono source-break source-sub">{summary(c.before)}</td>
                      <td className="gh-mono source-break source-sub">{summary(c.after)}</td>
                      <td className="source-sub">{c.userId ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={page} size={20} total={changes.data.total} onPage={setPage} />
          </>
        )}
    </section>
  );
}

function RuleForm({ rule, preset, settings, onSaved, onCancel }: {
  rule?: Rule; preset?: Partial<RuleRequest>; settings?: Settings; onSaved: () => void; onCancel: () => void;
}) {
  const [type, setType] = useState<ConnectionType>(rule?.connectionType ?? preset?.connectionType ?? 'JDBC');
  const [host, setHost] = useState(rule?.host ?? preset?.host ?? '');
  const [cidr, setCidr] = useState(rule?.cidr ?? '');
  const [port, setPort] = useState(rule ? String(rule.port) : preset?.port != null ? String(preset.port) : '');
  const [description, setDescription] = useState(rule?.description ?? '');
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body: RuleRequest = {
      connectionType: type, host: host.trim() || null, cidr: cidr.trim(), port: port.trim() ? Number(port) : null, enabled, description: description.trim() || null,
    };
    try {
      await api(rule ? `/api/security/connection-policies/${enc(rule.id)}` : '/api/security/connection-policies', { method: rule ? 'PUT' : 'POST', body, silent: true });
      onSaved();
    } catch (e2) {
      setError((e2 as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="source-form" onSubmit={(e) => void submit(e)} autoComplete="off">
      <div className="source-form-grid">
        <div className="form-group">
          <label htmlFor="secType">{t('연결 유형')}</label>
          <select id="secType" className="form-control" value={type} onChange={(e) => setType(e.target.value as ConnectionType)}>
            <option value="JDBC">{TYPE_LABEL.JDBC}</option>
            <option value="AI_HTTP">{TYPE_LABEL.AI_HTTP}</option>
          </select>
        </div>
        <div className="form-group">
          <label htmlFor="secPort">{t('포트')}</label>
          <input id="secPort" type="number" className="form-control gh-mono" required min={1} max={65535} value={port} onChange={(e) => setPort(e.target.value)} />
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="secHost">{t('호스트 (선택)')}</label>
          <input id="secHost" type="text" className="form-control gh-mono" maxLength={253} placeholder={t('예: db.internal.local · 127.0.0.1 (비우면 대역만 봅니다)')} value={host}
            onChange={(e) => setHost(e.target.value)} />
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="secCidr">{t('허용 대역 (CIDR)')}</label>
          <input id="secCidr" type="text" className="form-control gh-mono" required maxLength={64} placeholder={t('예: 10.10.0.0/16 · 127.0.0.1/32 · fd12::/48')} value={cidr}
            onChange={(e) => setCidr(e.target.value)} />
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="secDescription">{t('설명')}</label>
          <input id="secDescription" type="text" className="form-control" maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="form-group source-span-2 source-check">
          <label><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {t('활성 (끄면 이 규칙으로 연결하지 않습니다)')}</label>
        </div>
      </div>
      <p className="form-help">
        {t('와일드카드 호스트와 포트 범위는 쓸 수 없습니다. 너무 넓은 대역(IPv4 /8, IPv6 /16 보다 넓음)과 절대 차단 대역은 등록할 수 없습니다. localhost 처럼 IPv4·IPv6 둘 다로 풀리는 이름은 숫자 주소로 등록하세요.')}
        {settings && <> {t('항상 차단 : {0}', settings.blockedNetworks.join(', '))}</>}
      </p>
      {error && <p role="alert" className="source-error">{error}</p>}
      <div className="gh-dialog-actions">
        <span className="source-grow" />
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? t('저장 중…') : rule ? t('저장') : t('등록')}</button>
      </div>
    </form>
  );
}
