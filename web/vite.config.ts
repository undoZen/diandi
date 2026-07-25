import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// base: '/'（绝对）——客户端路由如 /spending 直接加载时，相对路径 ./assets/*
// 会被错解析成 /spending/assets/* 而 404；绝对路径才稳。
// outDir 直接写进 Android assets，由 Ktor staticResources("/", "web") 托管。
export default defineConfig({
  plugins: [react()],
  base: '/',
  resolve: {
    alias: {
      '@diandi/shared': path.resolve(__dirname, '../shared/src'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, '../app/src/main/assets/web'),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
  },
});
