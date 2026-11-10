import type { MenuId } from '../../hooks/useAuth';

/**
 * 메뉴 / 화면 경로 (예전 URL 그대로) - 노출 여부는 서버 MenuAuthorization(/api/auth/me 의 menus) 기준
 */
export interface MenuItem {
  id: MenuId;
  label: string;
  path: string;
}

export interface MenuGroup {
  id: MenuId;
  label: string;
  items: MenuItem[];
}

export const MENU_GROUPS: MenuGroup[] = [
  {
    id: 'operation-management',
    label: '운영 관리',
    items: [{ id: 'notice', label: '공지사항', path: '/notice' }],
  },
  {
    id: 'system-management',
    label: '시스템 관리',
    items: [
      { id: 'user-manage', label: '사용자 관리', path: '/userManage' },
      { id: 'project-manage', label: '프로젝트 관리', path: '/projectManage' },
      { id: 'system-log', label: '시스템 로그', path: '/systemLog' },
    ],
  },
  {
    id: 'data-management',
    label: '데이터 관리',
    items: [
      { id: 'data-dictionary', label: '데이터 사전', path: '/dataDictionary' },
      { id: 'table-manage', label: '테이블 관리', path: '/tableManage' },
      { id: 'column-manage', label: '컬럼 관리', path: '/columnManage' },
    ],
  },
  {
    id: 'data-modeling',
    label: '데이터 모델링',
    items: [
      { id: 'physical-erd', label: '물리 ERD', path: '/physicalErd' },
    ],
  },
];
