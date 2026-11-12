import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@xyflow/react/dist/style.css';
// 운영 화면과 같은 폰트·디자인 토큰 (--gh-*, 다크 모드)
import '../../src/styles/pretendard.css';
import '../../src/styles/primer.css';
import './erd.css';
import { followSystemTheme } from '../../src/lib/theme';
import { ErdPocApp } from './ErdPocApp';

followSystemTheme();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErdPocApp />
  </StrictMode>,
);
