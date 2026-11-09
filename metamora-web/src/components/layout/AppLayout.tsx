import { useState, type ReactNode } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { Footer } from './Footer';
import { Header } from './Header';
import { Sidebar } from './Sidebar';

/** 예전 layout.html : 헤더 + (로그인 시) 사이드바 + 콘텐츠 + 푸터 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <div className="page-container">
      <Header />
      <div className="main-content">
        {user && (
          <>
            <Sidebar mobileOpen={mobileOpen} />
            {/* 모바일용 토글 버튼 */}
            <button type="button" id="sidebarToggleBtn" className="sidebar-toggle-btn" aria-label="메뉴 열기" onClick={() => setMobileOpen((v) => !v)} />
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
