import type { MenuId } from '../../hooks/useAuth';
import { t } from '../../lib/i18n';

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
    label: t('운영 관리'),
    items: [{ id: 'notice', label: t('공지사항'), path: '/notice' }],
  },
  {
    id: 'system-management',
    label: t('시스템 관리'),
    items: [
      { id: 'user-manage', label: t('사용자 관리'), path: '/userManage' },
      { id: 'project-manage', label: t('프로젝트 관리'), path: '/projectManage' },
      { id: 'system-log', label: t('시스템 로그'), path: '/systemLog' },
    ],
  },
  {
    id: 'data-management',
    label: t('데이터 관리'),
    items: [
      { id: 'data-dictionary', label: t('데이터 사전'), path: '/dataDictionary' },
      { id: 'table-manage', label: t('테이블 관리'), path: '/tableManage' },
      { id: 'column-manage', label: t('컬럼 관리'), path: '/columnManage' },
    ],
  },
  {
    id: 'data-modeling',
    label: t('데이터 모델링'),
    items: [
      { id: 'physical-erd', label: t('물리 ERD'), path: '/physicalErd' },
    ],
  },
];
