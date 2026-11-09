import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth, type MenuId } from '../hooks/useAuth';
import { DeniedPage } from '../features/auth/DeniedPage';

/** 로그인이 필요한 화면 : 로그인하지 않았으면 로그인 화면으로 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { loading, user } = useAuth();
  const location = useLocation();
  if (loading) return null;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** 메뉴 권한이 필요한 화면 : 서버 메뉴 권한(MenuAuthorization)과 같은 기준. 데이터 보호는 각 API 의 @PreAuthorize 가 한다. */
export function RequireMenu({ menuId, children }: { menuId: MenuId; children: ReactNode }) {
  const { canView } = useAuth();
  return (
    <RequireAuth>
      {canView(menuId) ? children : <DeniedPage />}
    </RequireAuth>
  );
}

/** 로그인 전 화면(로그인/회원가입) : 이미 로그인했으면 대시보드로 */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { loading, user } = useAuth();
  if (loading) return null;
  if (user) return <Navigate to="/" replace />;
  return <>{children}</>;
}
