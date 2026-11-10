import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useAuth, type MenuId } from '../../hooks/useAuth';
// 사이드바 아이콘은 Lucide (그 외 화면은 GitHub Octicons - @primer/octicons-react)
import {Bell, ChevronDown, Database, LayoutGrid, Menu, SlidersHorizontal, Workflow} from 'lucide-react';
import { MENU_GROUPS } from './menu';

const GROUP_ICONS: Partial<Record<MenuId, ReactNode>> = {
  'operation-management': <Bell className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'system-management': <SlidersHorizontal className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'data-management': <Database className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
  'data-modeling': <Workflow className="sidebar-icon" strokeWidth={1.8} aria-hidden />,
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
  const currentGroup = MENU_GROUPS.find((g) => g.items.some((item) => location.pathname === item.path))?.id ?? null;
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
          aria-label={collapsed ? '사이드바 펼치기' : '사이드바 접기'}
          aria-expanded={!collapsed}
        >
          <Menu size={20} aria-hidden />
        </button>
      </div>

      <nav className="sidebar-nav">
        {canView('dashboard') && (
          <NavLink to="/" end className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`} title="대시보드">
            <LayoutGrid className="sidebar-icon" strokeWidth={1.8} aria-hidden />
            <span className="sidebar-label">대시보드</span>
          </NavLink>
        )}

        {MENU_GROUPS.filter((group) => canView(group.id)).map((group) => {
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
                {GROUP_ICONS[group.id]}
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
