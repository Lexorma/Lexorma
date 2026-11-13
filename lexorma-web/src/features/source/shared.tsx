import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { COLLECTABLE, dbTypeLabel, type SourceConnection } from './model';

export const CONNECTIONS_KEY = 'source-connections';

/** 로그인한 프로젝트의 원본 DB 연결 목록 (서버가 프로젝트로 거른다) */
export function useSourceConnections() {
  const { user } = useAuth();
  return useQuery({
    queryKey: [CONNECTIONS_KEY, user?.projectId],
    queryFn: () => api<SourceConnection[]>('/api/source/connections', { silent: true }),
    enabled: !!user,
  });
}

/**
 * 연결 관리·수정·삭제·테스트와 수집 실행·취소를 쓸 수 있는지 : 그 API 의 역할(USER 이상, menus 의 source-manage) +
 * 그 프로젝트의 관리자 또는 ADMIN (projectManageable). 서버가 같은 기준으로 다시 확인한다 — 화면 숨김은 보안을 대신하지 않는다.
 */
export function useCanManageSource() {
  const { canView, projectManageable } = useAuth();
  return canView('source-manage') && projectManageable;
}

/**
 * 화면에서 고른 연결 (?connectionId=) : 수집·카탈로그 화면이 같은 값을 쓰고, 화면 사이를 옮겨 다녀도 이어진다.
 * 고른 것이 없거나 목록에 없는 연결이면 첫 연결을 고른다.
 */
export function useSelectedConnection(connections: SourceConnection[] | undefined) {
  const [searchParams, setSearchParams] = useSearchParams();
  const raw = searchParams.get('connectionId');
  const selected = connections?.find((c) => String(c.id) === raw) ?? connections?.[0];

  useEffect(() => {
    if (selected && String(selected.id) !== raw) {
      setSearchParams((prev) => {
        const next = new URLSearchParams(prev);
        next.set('connectionId', String(selected.id));
        return next;
      }, { replace: true });
    }
  }, [selected, raw, setSearchParams]);

  const select = (id: number) => setSearchParams(() => new URLSearchParams({ connectionId: String(id) }));
  return { selected, select, searchParams, setSearchParams };
}

/** 연결 고르기 : 수집·카탈로그 화면의 맨 위 */
export function ConnectionPicker({ connections, selected, onSelect }: {
  connections: SourceConnection[];
  selected: SourceConnection | undefined;
  onSelect: (id: number) => void;
}) {
  return (
    <div className="form-group source-picker">
      <label htmlFor="sourceConnectionPicker">{t('연결')}</label>
      <select id="sourceConnectionPicker" className="form-control" value={selected?.id ?? ''} onChange={(e) => onSelect(Number(e.target.value))}>
        {connections.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name} · {dbTypeLabel(c.dbType)}{!COLLECTABLE.has(c.dbType) ? ` (${t('수집 미지원')})` : ''}{!c.enabled ? ` (${t('사용 중지')})` : ''}
          </option>
        ))}
      </select>
    </div>
  );
}

/** 연결이 없을 때 : 연결 관리로 안내 */
export function NoConnection() {
  return (
    <div className="source-empty" role="status">
      <p>{t('등록된 원본 DB 연결이 없습니다.')}</p>
      <p className="gh-muted">{t('원본 DB 관리 > 연결 관리에서 연결을 먼저 등록하세요.')}</p>
    </div>
  );
}

/** 쪽 이동 (서버의 page·size 쪽 단위 API) */
export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total <= size) return null;
  return (
    <nav className="source-pager" aria-label={t('쪽 이동')}>
      <button type="button" className="btn btn-sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>{t('이전')}</button>
      <span className="gh-muted">{t('{0} / {1} 쪽 · 전체 {2}건', page + 1, pages, total)}</span>
      <button type="button" className="btn btn-sm" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>{t('다음')}</button>
    </nav>
  );
}

/** 값이 없으면 '-' (서버가 아직 채우지 않은 건수를 0 으로 만들지 않는다) */
export const orDash = (value: unknown) => (value === null || value === undefined || value === '' ? '-' : String(value));
