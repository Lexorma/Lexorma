import type { MenuId } from '../../hooks/useAuth';
import { t } from '../../lib/i18n';

/**
 * 메뉴 / 화면 경로 (예전 URL 그대로) - 노출 여부는 서버 MenuAuthorization(/api/auth/me 의 menus) 기준.
 * 1뎁스는 하위 메뉴가 있는 그룹이다 (하위 없이 화면으로 바로 가는 단일 메뉴도 둘 수 있다). 순서 : 운영 → 시스템 → 사전 → 논리 → 물리 → 원본 DB → 품질.
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

export type MenuEntry = MenuGroup | MenuItem;

export const isGroup = (entry: MenuEntry): entry is MenuGroup => 'items' in entry;

export const MENU: MenuEntry[] = [
  {
    id: 'operation-management',
    label: t('운영 관리'),
    items: [{ id: 'notice', label: t('공지사항'), path: '/notice' }],
  },
  // 운영(공지)·시스템(사용자·프로젝트)은 다른 모든 정보의 바탕이라 대시보드 바로 다음에 둔다
  {
    id: 'system-management',
    label: t('시스템 관리'),
    items: [
      { id: 'user-manage', label: t('사용자 관리'), path: '/userManage' },
      { id: 'project-manage', label: t('프로젝트 관리'), path: '/projectManage' },
      { id: 'system-log', label: t('시스템 로그'), path: '/systemLog' },
      { id: 'connection-security', label: t('외부 연결 보안'), path: '/connectionSecurity' },
    ],
  },
  {
    id: 'data-dictionary',
    label: t('데이터 사전'),
    items: [
      { id: 'standard-domain', label: t('표준 도메인'), path: '/standardDomain' },
      { id: 'standard-word', label: t('표준 단어'), path: '/standardWord' },
      { id: 'standard-term', label: t('표준 용어'), path: '/standardTerm' },
    ],
  },
  {
    id: 'logical-modeling',
    label: t('논리 모델 관리'),
    items: [
      { id: 'logical-model', label: t('논리 모델 관리'), path: '/logicalModel' },
      { id: 'logical-erd', label: t('논리 ERD'), path: '/logicalErd' },
      { id: 'model-mapping', label: t('논리·물리 매핑'), path: '/modelMapping' },
    ],
  },
  {
    id: 'physical-modeling',
    label: t('물리 모델 관리'),
    items: [
      { id: 'schema-manage', label: t('스키마 관리'), path: '/schemaManage' },
      { id: 'table-manage', label: t('테이블 관리'), path: '/tableManage' },
      { id: 'column-manage', label: t('컬럼 관리'), path: '/columnManage' },
      { id: 'index-manage', label: t('인덱스 관리'), path: '/indexManage' },
      { id: 'view-manage', label: t('VIEW 관리'), path: '/viewManage' },
      { id: 'physical-erd', label: t('물리 ERD'), path: '/physicalErd' },
      { id: 'ddl-export', label: t('DDL 내보내기'), path: '/ddlExport' },
    ],
  },
  // 원본 DB 관리 (M-4a) : 외부 DB 에서 수집한 원본 카탈로그. 물리 모델과는 따로 저장된다
  {
    id: 'source-management',
    label: t('원본 DB 관리'),
    items: [
      { id: 'source-connection', label: t('연결 관리'), path: '/sourceConnection' },
      { id: 'source-collection', label: t('메타데이터 수집'), path: '/sourceCollection' },
      { id: 'source-catalog', label: t('원본 카탈로그'), path: '/sourceCatalog' },
      { id: 'source-erd', label: t('원본 DB ERD'), path: '/sourceErd' },
      { id: 'source-mapping', label: t('원본 매핑 관리'), path: '/sourceMapping' },
      { id: 'source-compare', label: t('원본·모델 비교'), path: '/sourceCompare' },
    ],
  },
  {
    id: 'data-quality',
    label: t('데이터 품질 관리'),
    items: [
      { id: 'standardization-quality', label: t('표준화 품질 진단'), path: '/standardizationQuality' },
      { id: 'model-history', label: t('모델 변경 이력'), path: '/modelHistory' },
      { id: 'model-version', label: t('모델 버전 관리'), path: '/modelVersion' },
    ],
  },
];
