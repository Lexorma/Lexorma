import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { initial } from '../../lib/format';
import { PersonIcon, SignOutIcon, ThreeBarsIcon } from '@primer/octicons-react';
import { t } from '../../lib/i18n';

/** GitHub 스타일 상단 헤더 : (모바일 메뉴) 로고 / 프로젝트 경로 + 사용자 메뉴 */
export function Header({ menuOpen, onMenuToggle }: { menuOpen?: boolean; onMenuToggle?: () => void }) {
  const { user, recovery, displayName, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const onLogout = async (e: React.MouseEvent) => {
    e.preventDefault();
    setOpen(false);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <header className="app-header">
      <div className="app-header-left">
        {onMenuToggle && (
          <button type="button" className="app-header-icon-btn app-header-menu-btn" aria-label={menuOpen ? t('메뉴 닫기') : t('메뉴 열기')}
            aria-expanded={menuOpen} onClick={onMenuToggle}>
            <ThreeBarsIcon />
          </button>
        )}
        <Link className="app-header-logo" to="/" aria-label={t('Lexorma 홈')}>
          <img src="/images/favicon.svg" alt="" width="20" height="20" />
        </Link>
        <nav className="app-header-context" aria-label={t('현재 위치')}>
          <Link className="app-header-crumb is-brand" to="/">
            Lexorma
          </Link>
          {user?.projectName && (
            <>
              <span className="app-header-sep" aria-hidden="true">/</span>
              <span className="app-header-crumb is-project">{user.projectName}</span>
            </>
          )}
        </nav>
      </div>

      <div className="app-header-right">
        {user && (
          <div className="dropdown" ref={menuRef} style={{ position: 'relative' }}>
            <button type="button" className="app-header-avatar" aria-expanded={open} aria-label={t('사용자 메뉴 열기')} onClick={() => setOpen((v) => !v)}>
              {initial(displayName)}
            </button>

            <div
              className={`dropdown-menu dropdown-menu-end app-header-menu${open ? ' show' : ''}`}
              style={{ position: 'absolute', right: 0, top: 'calc(100% + 4px)' }}
            >
              <div className="app-header-menu-user">
                <div className="app-header-avatar is-static">{initial(displayName)}</div>
                <div className="app-header-menu-names">
                  <strong>{displayName}</strong>
                  <span>{user.username}</span>
                </div>
              </div>
              {!recovery && (
                <>
                  <div className="app-header-menu-divider" />
                  <Link className="app-header-menu-item" to="/account" onClick={() => setOpen(false)}>
                    <PersonIcon />
                    {t('내 정보')}
                  </Link>
                </>
              )}
              <div className="app-header-menu-divider" />
              <a className="app-header-menu-item" href="/login" onClick={onLogout}>
                <SignOutIcon />
                {t('로그아웃')}
              </a>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
