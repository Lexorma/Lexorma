import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { prefersReducedMotion } from '../../lib/motion';
import { AiAssistant } from '../ai/AiAssistant';
import { Footer } from './Footer';
import { Header } from './Header';
import { Sidebar } from './Sidebar';

/** 예전 layout.html : 헤더 + (로그인 시) 사이드바 + 콘텐츠 + 푸터 */
export function AppLayout({ children }: { children: ReactNode }) {
  const { user, recovery, loading } = useAuth();
  // 복구 세션에는 업무 메뉴(사이드바)가 없다
  const showSidebar = !!user && !recovery;
  const [mobileOpen, setMobileOpen] = useState(false);
  const { pathname } = useLocation();

  // 모바일 메뉴는 화면을 옮기면 닫는다
  useEffect(() => setMobileOpen(false), [pathname]);

  // 모바일 메뉴는 Esc 로도 닫는다 (바깥 누르기·메뉴 고르기와 같다)
  useEffect(() => {
    if (!mobileOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMobileOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [mobileOpen]);

  // 화면 들어올 때 짧은 등장 (UI-1) : 경로가 바뀔 때만 — 검색·정렬·페이지·쿼리 파라미터 변경에는 하지 않고, 화면 컴포넌트를 다시 만들지 않는다
  // (Web Animations 로 이 영역만 움직인다. 줄인 모션이면 하지 않는다)
  const sectionRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const section = sectionRef.current;
    if (!section || prefersReducedMotion() || typeof section.animate !== 'function') return;
    const animation = section.animate([{ opacity: 0, translate: '0 4px' }, { opacity: 1, translate: '0 0' }], { duration: 180, easing: 'cubic-bezier(0, 0, 0.2, 1)' });
    return () => animation.cancel();
  }, [pathname, loading]);

  return (
    <div className="page-container">
      <Header menuOpen={mobileOpen} onMenuToggle={showSidebar ? () => setMobileOpen((v) => !v) : undefined} />
      <div className="main-content">
        {/* 로그인 정보 확인 중(가드가 빈 화면을 그리는 짧은 순간)에는 본문 영역을 그리지 않는다 : 사이드바 없이 그린 본문·푸터가
            로그인 확인 뒤 사이드바·화면 내용에 밀려나는 레이아웃 이동을 없앤다 (UI-1). loading 은 첫 확인 동안만 true (다시 조회 중에는 false) */}
        {!loading && (
          <>
            {showSidebar && (
              <>
                <Sidebar mobileOpen={mobileOpen} />
                {/* 모바일 메뉴(서랍) 바깥을 누르면 닫는다 */}
                <div className={`sidebar-backdrop d-md-none${mobileOpen ? ' is-open' : ''}`} aria-hidden onClick={() => setMobileOpen(false)} />
              </>
            )}
            <div className="content">
              <section ref={sectionRef}>{children}</section>
              <Footer />
            </div>
          </>
        )}
      </div>
      {/* AI 도우미 (AI-4) : 업무 메뉴가 있는 세션에서만, 메뉴 권한 ai-metadata-query 가 있을 때 */}
      {showSidebar && <AiAssistant />}
    </div>
  );
}
