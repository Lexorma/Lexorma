import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * ERD PoC (M-0) 전용 - 운영 빌드(vite.config.ts, dist/)와 분리. Mock 데이터만 쓰고 API 를 부르지 않는다.
 * npm run poc:erd → http://localhost:5174 / npm run poc:erd:build → build/erd-poc (jar 에 포함되지 않음)
 */
export default defineConfig({
  root: 'poc/erd',
  plugins: [react()],
  server: { port: 5174, strictPort: true },
  preview: { port: 5174, strictPort: true },
  build: { outDir: '../../build/erd-poc', emptyOutDir: true, assetsInlineLimit: 4096 },
  worker: { format: 'es' },
});
