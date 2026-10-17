import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// 单页无客户端路由，base 用相对路径：同一份产物既能挂在个人主页子路径，也能由 Android Ktor 在 / 托管。
// --mode android 时直接写进 Android assets（覆盖旧 web/ 的产物）。
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  base: './',
  build: {
    outDir: mode === 'android'
      ? path.resolve(__dirname, '../app/src/main/assets/web')
      : path.resolve(__dirname, 'dist'),
    emptyOutDir: true,
  },
  server: { port: 5174, host: true },
}));
