import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import '../../styles/source.css';

/**
 * 선택형 내장 MCP (DEC-31) : 외부 MCP 클라이언트가 이 프로젝트의 메타데이터를 읽기 전용으로 조회하도록 허용한다. 운영 설정(lexorma.mcp.enabled)과 이 프로젝트의 사용 설정이
 * 둘 다 켜져 있고 유효한 토큰이 있어야 한다. 토큰 원문은 발급 직후 이 화면에서 한 번만 보이며 화면 상태에만 둔다 (저장소·캐시에 넣지 않는다, 닫으면 지운다).
 */
type TokenStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';
interface Token { id: number; name: string; prefix: string; status: TokenStatus; expiresAt: string; revokedAt: string | null; lastUsedAt: string | null; issuedBy: string | null; issuedAt: string | null }
interface Call { toolName: string; result: 'OK' | 'DENIED' | 'ERROR'; calledAt: string }
interface Settings { serverEnabled: boolean; projectEnabled: boolean; canManage: boolean; endpointPath: string; maxDays: number; tokens: Token[]; recentCalls: Call[] }
interface Issued { info: Token; token: string }

const KEY = ['mcp-settings'];
const STATUS: Record<TokenStatus, string> = { ACTIVE: t('사용 중'), EXPIRED: t('만료됨'), REVOKED: t('폐기됨') };

/** 오늘부터 days 일 뒤 (날짜 입력 값) */
function dateAfter(days: number) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

export function McpAccessPage() {
  const { canView } = useAuth();
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: KEY, queryFn: () => api<Settings>('/api/mcp', { silent: true }) });
  const [name, setName] = useState('');
  const [expires, setExpires] = useState(dateAfter(30));
  const [issued, setIssued] = useState<Issued | null>(null);
  const [busy, setBusy] = useState(false);
  const data = settings.data;
  const manage = !!data?.canManage && canView('mcp-manage');
  const endpoint = `${window.location.origin}${data?.endpointPath ?? '/mcp'}`;
  const refresh = () => void queryClient.invalidateQueries({ queryKey: KEY });

  const toggle = async (enabled: boolean) => {
    setBusy(true);
    try {
      queryClient.setQueryData(KEY, await api<Settings>('/api/mcp', { method: 'PUT', body: { enabled } }));
    } catch { /* 공통 알림 */ } finally {
      setBusy(false);
    }
  };
  const issue = async () => {
    setBusy(true);
    try {
      setIssued(await api<Issued>('/api/mcp/tokens', { method: 'POST', body: { name: name.trim(), expiresAt: `${expires}T23:59:59` } }));
      setName('');
      refresh();
    } catch { /* 공통 알림 */ } finally {
      setBusy(false);
    }
  };
  const revoke = (token: Token) => dialog.confirm(t('{0} 토큰을 폐기하시겠습니까? 이 토큰을 쓰는 MCP 클라이언트는 바로 연결이 끊기고 되돌릴 수 없습니다.', token.name), async () => {
    await api(`/api/mcp/tokens/${token.id}`, { method: 'DELETE' });
    refresh();
  });

  return (
    <>
      <PageHeader category={t('AI 관리')} title={t('MCP 연결')}
        description={t('외부 MCP 클라이언트(예 : Claude Code, IDE)가 이 프로젝트의 메타데이터를 읽기 전용으로 조회하게 합니다. SQL 실행·원본 DB 접근·모델 변경·원본 VIEW 정의는 제공하지 않습니다. AI Provider 설정과 무관합니다.')} />
      {settings.isError ? <p role="alert">{t('MCP 설정을 불러오지 못했습니다.')} {settings.error.message}</p>
        : !data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : (
          <>
            <section className="source-card" aria-label={t('MCP 상태')}>
              <h3>{t('상태')}</h3>
              <div className="source-labels">
                <Label variant={data.serverEnabled ? 'done' : undefined}>{data.serverEnabled ? t('서버 MCP 켜짐') : t('서버 MCP 꺼짐')}</Label>
                <Label variant={data.projectEnabled ? 'done' : undefined}>{data.projectEnabled ? t('이 프로젝트 사용') : t('이 프로젝트 사용 안 함')}</Label>
              </div>
              {!data.serverEnabled && <p className="source-warn">{t('운영 설정 LEXORMA_MCP_ENABLED(lexorma.mcp.enabled) 이 꺼져 있어 /mcp 엔드포인트가 열려 있지 않습니다. 서버 관리자에게 요청하세요.')}</p>}
              {manage && (
                <label className="source-sub">
                  <input type="checkbox" checked={data.projectEnabled} disabled={busy} onChange={(e) => void toggle(e.target.checked)} /> {t('이 프로젝트를 MCP 로 조회할 수 있게 합니다')}
                </label>
              )}
              {!data.canManage && <p className="gh-muted">{t('설정과 토큰은 프로젝트 관리자만 관리할 수 있습니다.')}</p>}
            </section>

            <section className="source-card" aria-label={t('연결 안내')}>
              <h3>{t('연결 안내')}</h3>
              <p className="gh-muted">{t('Streamable HTTP 방식입니다. 요청마다 Authorization: Bearer <토큰> 헤더를 보냅니다. 외부에서 접속할 때는 HTTPS(리버스 프록시) 뒤에 두세요.')}</p>
              <pre className="source-definition-text gh-mono">{`claude mcp add --transport http lexorma ${endpoint} \\\n  --header "Authorization: Bearer <${t('토큰')}>"`}</pre>
            </section>

            {manage && (
              <section className="source-card" aria-label={t('MCP 토큰')}>
                <h3>{t('접근 토큰')}</h3>
                <p className="gh-muted">{t('토큰은 이 프로젝트만 조회할 수 있고, 쓸 때마다 발급한 사람의 지금 권한을 다시 확인합니다. 원문은 발급 직후 한 번만 보입니다. 만료일은 필수이며 최대 {0}일입니다.', data.maxDays)}</p>
                <div className="source-form-row">
                  <input className="form-control" aria-label={t('토큰 이름')} placeholder={t('토큰 이름 (용도)')} maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
                  <label htmlFor="mcp-expires">{t('만료일')}</label>
                  <input id="mcp-expires" type="date" className="form-control" min={dateAfter(1)} max={dateAfter(data.maxDays)} value={expires} onChange={(e) => setExpires(e.target.value)} />
                  <button type="button" className="btn btn-primary" disabled={busy || !name.trim() || !expires} onClick={() => void issue()}>{t('토큰 발급')}</button>
                </div>
                {issued && (
                  <div className="source-warn" role="alert">
                    <p>{t('{0} 토큰의 원문입니다. 지금 복사하세요 — 다시 볼 수 없습니다.', issued.info.name)}</p>
                    <pre className="source-definition-text gh-mono" aria-label={t('토큰 원문')}>{issued.token}</pre>
                    <div className="source-actions">
                      <button type="button" className="btn btn-sm" onClick={() => void navigator.clipboard?.writeText(issued.token)}>{t('복사')}</button>
                      <button type="button" className="btn btn-sm" onClick={() => setIssued(null)}>{t('닫기')}</button>
                    </div>
                  </div>
                )}
                {data.tokens.length === 0 ? <p className="gh-muted">{t('발급한 토큰이 없습니다.')}</p> : (
                  <div className="source-table-wrap">
                    <table className="source-table">
                      <thead><tr><th>{t('이름')}</th><th>{t('앞부분')}</th><th>{t('상태')}</th><th>{t('만료')}</th><th>{t('마지막 사용')}</th><th>{t('발급')}</th><th /></tr></thead>
                      <tbody>
                        {data.tokens.map((token) => (
                          <tr key={token.id}>
                            <td>{token.name}</td>
                            <td className="gh-mono">{token.prefix}…</td>
                            <td><Label variant={token.status === 'ACTIVE' ? 'done' : 'danger'}>{STATUS[token.status]}</Label></td>
                            <td>{formatDateTime(token.expiresAt)}</td>
                            <td>{token.lastUsedAt ? formatDateTime(token.lastUsedAt) : '-'}</td>
                            <td className="gh-muted">{token.issuedBy ?? '-'} {token.issuedAt ? formatDateTime(token.issuedAt) : ''}</td>
                            <td>{token.status === 'ACTIVE' && <button type="button" className="btn btn-sm btn-danger" onClick={() => revoke(token)}>{t('폐기')}</button>}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <h4>{t('최근 호출')}</h4>
                {data.recentCalls.length === 0 ? <p className="gh-muted">{t('호출 기록이 없습니다.')}</p> : (
                  <ul className="source-sub">
                    {data.recentCalls.map((call, i) => (
                      <li key={i}><span className="gh-mono">{call.toolName}</span> · <Label variant={call.result === 'OK' ? 'done' : 'danger'}>{call.result}</Label> · {formatDateTime(call.calledAt)}</li>
                    ))}
                  </ul>
                )}
              </section>
            )}
          </>
        )}
    </>
  );
}
