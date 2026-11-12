import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { ApiError } from './lib/api';
import { followSystemTheme } from './lib/theme';

// 스타일 순서 : 레이아웃 → 기본 요소(base, 예전 Bootstrap 자리) → dataTables → Primer 덮어쓰기 → 화면별
import './styles/layout.css';
import './styles/base.css';
import './styles/dataTables.dataTables.min.css';
import './styles/pretendard.css';
import './styles/primer.css';
import './styles/popup.css';
import './styles/notice.css';
import './styles/column_table_detail.css';
import './styles/auth.css';
import './styles/account.css';
import './styles/denied.css';
import './styles/dashboard.css';
import './styles/notice_list.css';
import './styles/help.css';
import './styles/project_manage.css';

// 공통 알림을 이미 띄운 API 오류는 콘솔의 처리되지 않은 Promise 오류로 남기지 않는다
window.addEventListener('unhandledrejection', (event) => {
  if (event.reason instanceof ApiError && event.reason.handled) event.preventDefault();
});

followSystemTheme();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
