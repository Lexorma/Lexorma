import { tc } from './i18n';

/**
 * 권한 표시명. 서버의 권한 설명(TB_ROLE.ROLE_EXPLN)은 영문 고정이라 화면 언어에 맞춰 여기서 바꿔 보여 준다.
 * 권한 코드(ROLE_ADMIN), 지금의 영문 설명(Administrator), 예전 한글 설명(관리자 — 이전에 만든 DB·시스템 로그에 남아 있다) 모두 받는다.
 */
const LABELS: Record<string, string> = {
  ROLE_ADMIN: tc('권한', '관리자'),
  ROLE_MANAGER: tc('권한', '매니저'),
  ROLE_DBA: tc('권한', 'DBA'),
  ROLE_USER: tc('권한', '사용자'),
  ROLE_VIEWER: tc('권한', '뷰어 사용자'),
  ROLE_NOT_APPROVE: tc('권한', '권한 미승인 사용자'),
  ROLE_ANONYMOUS: tc('권한', '익명 사용자'),
};

const CODE_BY_DESCRIPTION: Record<string, string> = {
  Administrator: 'ROLE_ADMIN', '관리자': 'ROLE_ADMIN',
  Manager: 'ROLE_MANAGER', '매니저': 'ROLE_MANAGER',
  'Database Administrator': 'ROLE_DBA', DBA: 'ROLE_DBA',
  User: 'ROLE_USER', '사용자': 'ROLE_USER',
  Viewer: 'ROLE_VIEWER', '뷰어 사용자': 'ROLE_VIEWER',
  'User Pending Approval': 'ROLE_NOT_APPROVE', '권한 미승인 사용자': 'ROLE_NOT_APPROVE',
  'Anonymous User': 'ROLE_ANONYMOUS', '익명 사용자': 'ROLE_ANONYMOUS',
};

/** 권한 코드 또는 권한 설명 → 화면 언어의 권한명. 모르는 값(직접 추가한 권한)은 그대로 보여 준다 */
export function roleLabel(value: unknown): string {
  const text = String(value ?? '');
  return LABELS[text] ?? LABELS[CODE_BY_DESCRIPTION[text]] ?? text;
}
