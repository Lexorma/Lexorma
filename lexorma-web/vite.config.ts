import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 개발 : npm run dev (http://localhost:5173) → /api 는 Spring Boot(9940)로 프록시 (세션 쿠키 / CSRF 그대로 동작)
 * 운영 : npm run build → dist/ 를 Gradle 이 lexorma.jar 의 static/ 에 넣는다 (별도 프론트 서버 없음)
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:9940',
        changeOrigin: true,
      },
      '/swagger-ui': 'http://localhost:9940',
      '/v3/api-docs': 'http://localhost:9940',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 폰트(약 2MB)는 인라인하지 않고 해시 파일(/assets)로 둔다 → 1년 캐시
    assetsInlineLimit: 4096,
  },
});
