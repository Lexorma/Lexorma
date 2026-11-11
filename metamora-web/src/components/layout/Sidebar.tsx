import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth, type MenuId } from '../../hooks/useAuth';
// 사이드바 아이콘은 Lucide (그 외 화면은 GitHub Octicons - @primer/octicons-react)
import {Bell, BookOpen, ChevronDown, ClipboardCheck, Database, LayoutGrid, Menu, SlidersHorizontal, Workflow} from 'lucide-react';
import { isGroup, MENU, type MenuGroup } from './menu';
import { t } from '../../lib/i18n';

const MENU_ICONS: Partial<Record<MenuId, ReactNode>> = {
  'operation-management': <Bell className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'system-management': <SlidersHorizontal className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'data-dictionary': <BookOpen className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'logical-modeling': <Workflow className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'physical-modeling': <Database className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'data-quality': <ClipboardCheck className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
};

function isCollapsed() {
  return document.documentElement.classList.contains('sidebar-collapsed');
}

/** PC 사이드바 접기/펼치기 (상태는 브라우저에 기억) */
export function toggleSidebarCollapse(): boolean {
  const collapsed = document.documentElement.classList.toggle('sidebar-collapsed');
  try {
    localStorage.setItem('sidebarCollapsed', String(collapsed));
  } catch {
    // 저장소를 쓸 수 없으면 이번 화면에서만 유지
  }
  return collapsed;
}

export function Sidebar({ mobileOpen }: { mobileOpen: boolean }) {
  const { canView } = useAuth();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(isCollapsed);

  // 현재 화면이 속한 메뉴 그룹을 연다
  const currentGroup = MENU.filter(isGroup).find((g) => g.items.some((item) => location.pathname === item.path))?.id ?? null;
  const [openGroup, setOpenGroup] = useState<MenuId | null>(currentGroup);
  useEffect(() => {
    if (currentGroup) setOpenGroup(currentGroup);
  }, [currentGroup]);

  const toggleGroup = (groupId: MenuId) => {
    // 사이드바가 접혀 있으면 펼치면서 해당 메뉴를 연다
    if (isCollapsed()) {
      setCollapsed(toggleSidebarCollapse());
      setOpenGroup(groupId);
      return;
    }
    setOpenGroup((prev) => (prev === groupId ? null : groupId));
  };

  return (
    <aside className={`sidebar ${mobileOpen ? 'd-block' : 'd-none'} d-md-block`}>
      <div className="sidebar-header">
        <span className="sidebar-title">MENU</span>
        <button
          type="button"
          className="sidebar-collapse-btn"
          onClick={() => setCollapsed(toggleSidebarCollapse())}
          aria-label={collapsed ? t('사이드바 펼치기') : t('사이드바 접기')}
          aria-expanded={!collapsed}
        >
          <Menu size={20} aria-hidden />
        </button>
      </div>

      <nav className="sidebar-nav">
        {canView('dashboard') && (
          <NavLink to="/" end className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`} title={t('대시보드')}>
            <LayoutGrid className="sidebar-icon" strokeWidth={1.8} aria-hidden />
            <span className="sidebar-label">{t('대시보드')}</span>
          </NavLink>
        )}

        {MENU.filter((entry) => canView(entry.id)).map((entry) => {
          // 하위 메뉴가 없는 1뎁스 메뉴 : 대시보드처럼 바로 이동한다
          if (!isGroup(entry)) {
            return (
              <NavLink key={entry.id} to={entry.path} className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`} title={entry.label}>
                {MENU_ICONS[entry.id]}
                <span className="sidebar-label">{entry.label}</span>
              </NavLink>
            );
          }
          const group: MenuGroup = entry;
          const open = openGroup === group.id && !collapsed;
          return (
            <Fragment key={group.id}>
              <a
                href={`#${group.id}`}
                className={`sidebar-link has-children${open || currentGroup === group.id ? ' active' : ''}`}
                title={group.label}
                aria-expanded={open}
                onClick={(e) => {
                  e.preventDefault();
                  toggleGroup(group.id);
                }}
              >
                {MENU_ICONS[group.id]}
                <span className="sidebar-label">{group.label}</span>
                <ChevronDown className="sidebar-chevron" aria-hidden />
              </a>
              <div id={group.id} className={`collapse sidebar-submenu${open ? ' show' : ''}`}>
                {group.items
                  .filter((item) => canView(item.id))
                  .map((item) => (
                    <NavLink key={item.id} to={item.path} className={({ isActive }) => `sidebar-sublink${isActive ? ' active' : ''}`}>
                      {item.label}
                    </NavLink>
                  ))}
              </div>
            </Fragment>
          );
        })}
      </nav>
    </aside>
  );
}
