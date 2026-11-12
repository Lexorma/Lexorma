import { useEffect, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { Footer } from './Footer';
import { Header } from './Header';
import { Sidebar } from './Sidebar';

/** 예전 layout.html : 헤더 + (로그인 시) 사이드바 + 콘텐츠 + 푸터 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { user, recovery } = useAuth();
  // 복구 세션에는 업무 메뉴(사이드바)가 없다
  const showSidebar = !!user && !recovery;
  const [mobileOpen, setMobileOpen] = useState(false);
  const { pathname } = useLocation();

  // 모바일 메뉴는 화면을 옮기면 닫는다
  useEffect(() => setMobileOpen(false), [pathname]);

  return (
    <div className="page-container">
      <Header menuOpen={mobileOpen} onMenuToggle={showSidebar ? () => setMobileOpen((v) => !v) : undefined} />
      <div className="main-content">
        {showSidebar && (
          <>
            <Sidebar mobileOpen={mobileOpen} />
            {/* 모바일 메뉴(서랍) 바깥을 누르면 닫는다 */}
            {mobileOpen && <div className="sidebar-backdrop d-md-none" aria-hidden onClick={() => setMobileOpen(false)} />}
          </>
        )}
        <div className="content">
          <section>{children}</section>
          <Footer />
        </div>
      </div>
    </div>
  );
}
