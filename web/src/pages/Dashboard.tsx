import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api';
import { dayKeyPad, yuan } from '@diandi/shared';
import type { SpendingItem } from '@diandi/shared';

const RANGES = [7, 30, 90];
const DAYS_KEY = 'dashDays';

export default function Dashboard() {
  const [days, setDays] = useState<number>(() => {
    try {
      const raw = localStorage.getItem(DAYS_KEY);
      const v = raw ? +raw : 30;
      return RANGES.includes(v) ? v : 30;
    } catch {
      return 30;
    }
  });
  const [items, setItems] = useState<SpendingItem[]>([]);

  const load = useCallback(async (d: number) => {
    try {
      setItems(await api.spending({ days: d, limit: 500 }));
    } catch {
      /* 忽略 */
    }
  }, []);

  useEffect(() => {
    load(days);
  }, [days, load]);

  // 回到前台自动刷新
  useEffect(() => {
    const onVis = () => {
      if (document.visibilityState === 'visible') load(days);
    };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, [days, load]);

  const pickDays = (d: number) => {
    setDays(d);
    try {
      localStorage.setItem(DAYS_KEY, String(d));
    } catch {
      /* ignore */
    }
  };

  const total = items.reduce((s, r) => s + r.amountCents, 0);
  const byCat = new Map<string, number>();
  const byDay = new Map<string, number>();
  for (const r of items) {
    const c = r.category || 'Unknown';
    byCat.set(c, (byCat.get(c) || 0) + r.amountCents);
    const k = dayKeyPad(r.txTime);
    byDay.set(k, (byDay.get(k) || 0) + r.amountCents);
  }
  const cats = [...byCat.entries()].sort((a, b) => b[1] - a[1]);
  const maxCat = cats.length ? cats[0][1] : 1;

  // 连续日期轴，最多 30 根
  const barsWanted = Math.min(days, 30);
  const keys: string[] = [];
  let maxDay = 1;
  for (let i = barsWanted - 1; i >= 0; i--) {
    const k = dayKeyPad(Date.now() - i * 86_400_000);
    keys.push(k);
    const v = byDay.get(k) || 0;
    if (v > maxDay) maxDay = v;
  }

  return (
    <div className="mx-auto max-w-2xl p-3">
      <div className="mb-4 flex gap-2">
        {RANGES.map((d) => (
          <button
            key={d}
            onClick={() => pickDays(d)}
            className={`rounded-full border px-3 py-1 text-xs ${
              d === days
                ? 'border-[#2d5a88] bg-[#2d5a88] text-white'
                : 'border-ink-line text-ink-muted'
            }`}
          >
            {d} 天
          </button>
        ))}
      </div>

      <div className="my-5 text-center">
        <div className="text-3xl font-bold tabular-nums">¥{yuan(total)}</div>
        <div className="mt-1 text-xs text-ink-muted">
          {items.length} 笔 · 日均 ¥{yuan(Math.round(total / days))}
        </div>
      </div>

      {items.length === 0 ? (
        <div className="py-12 text-center text-ink-muted">该时间段没有消费记录</div>
      ) : (
        <>
          <h2 className="mb-2 text-xs text-ink-muted">分类占比</h2>
          <div className="space-y-2">
            {cats.map(([c, v]) => {
              const pct = total ? Math.round((v * 100) / total) : 0;
              return (
                <div key={c} className="flex items-center gap-2">
                  <span className="w-[5.5em] shrink-0 text-right text-xs">{c}</span>
                  <div className="h-4 flex-1 overflow-hidden rounded bg-ink-card">
                    <div
                      className="h-full rounded bg-gradient-to-r from-[#2d5a88] to-[#4a8ec7]"
                      style={{ width: `${(v * 100) / maxCat}%` }}
                    />
                  </div>
                  <span className="w-[6.5em] shrink-0 text-xs tabular-nums">
                    ¥{yuan(v)} <span className="text-ink-muted">{pct}%</span>
                  </span>
                </div>
              );
            })}
          </div>

          <h2 className="mb-2 mt-5 text-xs text-ink-muted">按日趋势</h2>
          <div className="mt-2 flex gap-[3px]">
            {keys.map((k, idx) => {
              const v = byDay.get(k) || 0;
              const h = v ? Math.max(3, (v * 100) / maxDay) : 0;
              const label =
                idx === 0 || idx === keys.length - 1 || idx % 5 === 0
                  ? k.slice(5).replace('-', '/')
                  : '';
              return (
                <div key={k} className="relative min-w-0 flex-1 pb-4">
                  <div className="flex h-[100px] items-end justify-center">
                    <div
                      className="min-h-[2px] w-[70%] max-w-[26px] rounded-t bg-gradient-to-b from-ink-warn to-[#a87c0e]"
                      style={{ height: `${h}%` }}
                    />
                  </div>
                  <span className="absolute inset-x-0 bottom-0 whitespace-nowrap text-center text-[0.55rem] text-ink-muted/70">
                    {label}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
