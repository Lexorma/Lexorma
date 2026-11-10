import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, resetCsrf } from '../lib/api';

export type MenuId =
  | 'dashboard'
  | 'user-manage'
  | 'project-manage'
  | 'system-log'
  | 'notice'
  | 'data-dictionary'
  | 'table-manage'
  | 'physical-erd'
  | 'column-manage'
  | 'system-management'
  | 'operation-management'
  | 'data-management'
  | 'data-modeling';

export interface AuthUser {
  id: string;
  username: string;
  name: string | null;
  role: string;
  roleName: string;
  projectId: number;
  projectName: string | null;
}

export interface Me {
  authenticated: boolean;
  user?: AuthUser;
  menus?: Partial<Record<MenuId, boolean>>;
  approvalAvailable?: boolean;
  code?: string;
}

interface AuthContextValue {
  me: Me | undefined;
  loading: boolean;
  user: AuthUser | undefined;
  /** 화면에 보이는 이름 (이름이 없으면 아이디) */
  displayName: string;
  canView: (menuId: MenuId) => boolean;
  approvalAvailable: boolean;
  /** 로그인 상태를 서버에서 다시 받는다 (로그인 직후, 이름 변경 후) */
  refresh: () => Promise<void>;
  /** 로그아웃 : 서버 세션 무효화 → 화면 상태 초기화 */
  logout: () => Promise<void>;
  /** 세션 만료 등으로 서버에서 이미 끊긴 경우 화면 상태만 비운다 */
  clear: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const ME_QUERY_KEY = ['auth', 'me'];

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const { data, isPending } = useQuery({
    queryKey: ME_QUERY_KEY,
    queryFn: () => api<Me>('/api/auth/me', { silent: true }),
    staleTime: 60_000,
    retry: false,
  });

  const clear = useCallback(() => {
    resetCsrf();
    // 다른 사용자의 화면 데이터가 남지 않도록 캐시를 모두 비운다
    queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== 'auth' });
    queryClient.setQueryData<Me>(ME_QUERY_KEY, { authenticated: false });
  }, [queryClient]);

  const refresh = useCallback(async () => {
    resetCsrf();
    await queryClient.invalidateQueries({ queryKey: ME_QUERY_KEY });
    await queryClient.refetchQueries({ queryKey: ME_QUERY_KEY });
  }, [queryClient]);

  const logout = useCallback(async () => {
    try {
      await api('/api/auth/logout', { method: 'POST', silent: true });
    } finally {
      clear();
    }
  }, [clear]);

  const value = useMemo<AuthContextValue>(() => {
    const user = data?.authenticated ? data.user : undefined;
    return {
      me: data,
      loading: isPending,
      user,
      displayName: user ? (user.name?.trim() ? user.name : user.username) : '',
      canView: (menuId) => !!data?.menus?.[menuId],
      approvalAvailable: !!data?.approvalAvailable,
      refresh,
      logout,
      clear,
    };
  }, [data, isPending, refresh, logout, clear]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('AuthProvider 안에서 사용해야 합니다.');
  return ctx;
}
