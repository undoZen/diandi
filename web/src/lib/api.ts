// SPA 与 /api/* 同源（都由 Ktor 在 127.0.0.1:PORT 托管），用 window.location.origin
// 作 baseUrl 可自动适配动态端口（8899→8894 fallback）。
import { createApiClient } from '@diandi/shared';

export const api = createApiClient(
  typeof window !== 'undefined' ? window.location.origin : 'http://127.0.0.1:8899',
);
