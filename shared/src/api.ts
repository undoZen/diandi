import type {
  NotificationItem,
  NotificationParams,
  PingResponse,
  SpendingItem,
  SpendingParams,
} from './types';

export interface ApiClient {
  ping(): Promise<PingResponse>;
  notifications(params?: NotificationParams): Promise<NotificationItem[]>;
  spending(params?: SpendingParams): Promise<SpendingItem[]>;
  spendingCategories(): Promise<string[]>;
  /** 修正一条消费的分类，返回更新后的记录；修正会触发服务端 retrain */
  correctCategory(id: number, category: string): Promise<SpendingItem>;
  /** 手动录入消费文本 → 解析+分类+落库，返回新记录 */
  manualEntry(text: string): Promise<SpendingItem>;
}

export function createApiClient(baseUrl: string): ApiClient {
  const j = async <T>(path: string, init?: RequestInit): Promise<T> => {
    const res = await fetch(baseUrl + path, init);
    if (!res.ok) throw new Error(`${res.status} ${res.statusText} @ ${path}`);
    return (await res.json()) as T;
  };

  const withQuery = (path: string, params?: Record<string, string | number | undefined>) => {
    const pairs: string[] = [];
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null && v !== '') pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
      }
    }
    return pairs.length ? `${path}?${pairs.join('&')}` : path;
  };

  return {
    ping: () => j('/api/ping'),
    notifications: (p) => j(withQuery('/api/notifications', { limit: p?.limit, filter: p?.filter })),
    spending: (p) => j(withQuery('/api/spending', { limit: p?.limit, days: p?.days })),
    spendingCategories: () => j('/api/spending/categories'),
    correctCategory: (id, category) =>
      j(`/api/spending/${id}/category`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ category }),
      }),
    manualEntry: (text) =>
      j('/api/spending/manual', {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: text,
      }),
  };
}
