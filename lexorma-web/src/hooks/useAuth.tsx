import { createContext, useCallback, useContext, useEffect, useMemo, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, resetCsrf } from '../lib/api';

export type MenuId =
  | 'dashboard'
  | 'user-manage'
  | 'project-manage'
  | 'system-log'
  | 'notice'
  /** 화면이 아니라 공지 등록·수정·삭제 버튼 노출 여부 (현재 프로젝트의 관리자인지는 /api/selectNotice/permission) */
  | 'notice-edit'
  | 'data-dictionary'
  | 'standard-domain'
  | 'standard-word'
  | 'standard-term'
  /** 화면이 아니라 데이터 사전 등록·수정·삭제·엑셀 버튼 노출 여부 */
  | 'dictionary-edit'
  /** 화면이 아니라 데이터 사전 승인·승인취소 버튼 노출 여부 (approvalAvailable 과 함께 본다) */
  | 'dictionary-approve'
  | 'data-quality'
  | 'logical-model'
  | 'logical-erd'
  /** 화면이 아니라 논리 모델 변경 버튼(등록·수정·삭제) 노출 여부 */
  | 'logical-model-edit'
  | 'model-mapping'
  /** 화면이 아니라 매핑 등록·변경·해제 버튼 노출 여부 */
  | 'model-mapping-edit'
  /** 화면이 아니라 물리 모델 변경 버튼(스키마·테이블 관리의 등록·수정·삭제·엑셀, 물리 ERD 편집 모드) 노출 여부 */
  | 'physical-model-edit'
  | 'schema-manage'
  | 'table-manage'
  | 'physical-erd'
  | 'column-manage'
  | 'standardization-quality'
  | 'model-history'
  | 'system-management'
  | 'operation-management'
  | 'logical-modeling'
  | 'physical-modeling';

export interface AuthUser {
  id: string;
  username: string;
  name: string | null;
  role: string;
  roleName: string;
  /** 복구 세션(recovery)에서는 null 이지만 그때는 프로젝트를 쓰는 화면에 들어갈 수 없다 */
  projectId: number;
  projectName: string | null;
}

export interface Me {
  authenticated: boolean;
  /** 복구 세션 (활성 프로젝트가 없을 때 시스템 ADMIN 의 복구 로그인) : 복구 화면만 쓸 수 있다 */
  recovery?: boolean;
  user?: AuthUser;
  menus?: Partial<Record<MenuId, boolean>>;
  approvalAvailable?: boolean;
  code?: string;
}

interface AuthContextValue {
  me: Me | undefined;
  loading: boolean;
  user: AuthUser | undefined;
  /** 복구 세션인지 (서버도 복구 API 외에는 모두 막는다) */
  recovery: boolean;
  /** 화면에 보이는 이름 (이름이 없으면 아이디) */
  displayName: string;
  /**
   * 화면(메뉴) 또는 액션(…-edit, …-approve)을 쓸 수 있는지. 서버 MenuAuthorization 이 역할 계층으로 계산해 내려준 값이다
   * (화면에서 역할 이름을 직접 비교하지 않는다). 화면에 들어올 수 있다고 그 화면의 변경 버튼까지 보이는 것은 아니다.
   */
  canView: (menuId: MenuId) => boolean;
  /** 승인 권한(ADMIN·MANAGER·프로젝트 관리자)이면서 변경 API 를 쓸 수 있는 역할(USER 이상)인지 */
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

  // 뒤로 가기로 브라우저가 보관해 둔 화면(bfcache)이 되살아나면 로그아웃 전 상태가 그대로 보인다 → 로그인 상태를 다시 받는다
  useEffect(() => {
    const onPageShow = (e: PageTransitionEvent) => {
      if (e.persisted) void refresh();
    };
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, [refresh]);

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
      recovery: !!user && !!data?.recovery,
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
