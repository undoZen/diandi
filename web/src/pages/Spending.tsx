import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api';
import { dayKey, dayLabel, hm, yuan } from '@diandi/shared';
import type { SpendingItem } from '@diandi/shared';

const SEEN_KEY = 'spendingSeenAt';

export default function Spending() {
  const [cats, setCats] = useState<string[]>([]);
  const [rows, setRows] = useState<SpendingItem[]>([]);
  const [lastSeen, setLastSeen] = useState<number>(() => {
    try {
      const raw = localStorage.getItem(SEEN_KEY);
      return raw ? +raw : 0;
    } catch {
      return 0;
    }
  });
  const [sheetId, setSheetId] = useState<number | null>(null);
  const [manualOpen, setManualOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      setRows(await api.spending({ limit: 300 }));
    } catch {
      /* 忽略，下次切回前台再试 */
    }
  }, []);

  useEffect(() => {
    api.spendingCategories().then(setCats).catch(() => {});
    load();
  }, [load]);

  const touchSeen = useCallback(() => {
    const now = Date.now();
    setLastSeen(now);
    try {
      localStorage.setItem(SEEN_KEY, String(now));
    } catch {
      /* localStorage 不可用时无碍 */
    }
  }, []);

  // 回到前台自动刷新；离开时推进未读水位（浏览中保持高亮，下次进来归零）
  useEffect(() => {
    const onVis = () => {
      if (document.visibilityState === 'visible') load();
      else touchSeen();
    };
    document.addEventListener('visibilitychange', onVis);
    window.addEventListener('pagehide', touchSeen);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      window.removeEventListener('pagehide', touchSeen);
    };
  }, [load, touchSeen]);

  const sheetRow = sheetId != null ? rows.find((r) => r.id === sheetId) ?? null : null;

  const correct = async (cat: string) => {
    if (!sheetRow) return;
    const row = sheetRow;
    const old = { category: row.category, corrected: row.corrected, uncertain: row.uncertain };
    // 乐观更新
    setRows((rs) =>
      rs.map((r) => (r.id === row.id ? { ...r, category: cat, corrected: true, uncertain: false } : r)),
    );
    setSheetId(null);
    try {
      await api.correctCategory(row.id, cat);
      await load(); // 拉最新：修正结果 + 期间可能新到的消费
    } catch {
      setRows((rs) => rs.map((r) => (r.id === row.id ? { ...r, ...old } : r)));
      alert('保存失败，请重试');
    }
  };

  // 按日分组（保持到达顺序）
  const groups: { key: string; items: SpendingItem[] }[] = [];
  const groupMap = new Map<string, SpendingItem[]>();
  for (const r of rows) {
    const k = dayKey(r.txTime);
    let g = groupMap.get(k);
    if (!g) {
      g = [];
      groupMap.set(k, g);
      groups.push({ key: k, items: g });
    }
    g.push(r);
  }
  const newCount = rows.filter((r) => r.createdAt > lastSeen).length;
  let shownDivider = false;

  return (
    <div>
      <div className="flex items-baseline gap-2 px-4 py-2 text-sm">
        <span className="text-ink-muted">
          {rows.length} 笔{newCount > 0 ? ` · ${newCount} 新` : ''}
        </span>
        <button
          onClick={() => setManualOpen(true)}
          className="ml-auto font-semibold text-ink-warn"
        >
          ＋录入
        </button>
      </div>

      {rows.length === 0 ? (
        <div className="py-12 text-center text-ink-muted">
          暂无消费记录（等一条银行通知，或检查通知使用权）
        </div>
      ) : (
        groups.map((g) => {
          const sum = g.items.reduce((s, r) => s + r.amountCents, 0);
          return (
            <div key={g.key}>
              <div className="sticky top-14 z-[5] flex justify-between bg-ink-base px-4 py-2 text-xs text-ink-muted">
                <span>
                  {dayLabel(g.items[0].txTime)} · {g.items.length}笔
                </span>
                <span>¥{yuan(sum)}</span>
              </div>
              <ul className="space-y-2 px-2 pb-2">
                {g.items.map((r) => {
                  const isNew = r.createdAt > lastSeen;
                  let divider = null;
                  if (!isNew && !shownDivider) {
                    shownDivider = true;
                    divider = (
                      <li className="border-b border-ink-line py-2 text-center text-[0.7rem] text-ink-muted/60">
                        以下记录已经确认处理过
                      </li>
                    );
                  }
                  return (
                    <div key={r.id}>
                      {divider}
                      <li
                        className={`flex items-center gap-2 rounded-xl bg-ink-card px-3 py-2 ${
                          isNew ? 'border-l-2 border-ink-warn' : ''
                        }`}
                      >
                        <div className="min-w-0 flex-1">
                          {r.merchant ? (
                            <div className="truncate text-sm font-semibold">{r.merchant}</div>
                          ) : (
                            <div className="text-sm font-normal text-ink-muted">（无商户）</div>
                          )}
                          <div className="mt-0.5 text-[0.7rem] text-ink-muted/80">
                            {hm(r.txTime)}
                            {r.channel ? ` · ${r.channel}` : ''}
                          </div>
                        </div>
                        <Chip row={r} onClick={() => setSheetId(r.id)} />
                        <span className="whitespace-nowrap text-base font-bold tabular-nums">
                          ¥{yuan(r.amountCents)}
                        </span>
                      </li>
                    </div>
                  );
                })}
              </ul>
            </div>
          );
        })
      )}

      {sheetRow && (
        <Sheet row={sheetRow} cats={cats} onClose={() => setSheetId(null)} onPick={correct} />
      )}
      {manualOpen && (
        <ManualSheet
          onClose={() => setManualOpen(false)}
          onDone={() => {
            setManualOpen(false);
            load();
          }}
        />
      )}
    </div>
  );
}

function Chip({ row, onClick }: { row: SpendingItem; onClick: () => void }) {
  const base = 'whitespace-nowrap rounded-full border px-2 py-0.5 text-xs';
  if (row.corrected) {
    return (
      <button
        onClick={onClick}
        className={`${base} border-[#3a7d44] bg-[#24382a] text-[#9fd8a8]`}
      >
        ✓ {row.category}
      </button>
    );
  }
  if (row.uncertain) {
    return (
      <button
        onClick={onClick}
        className={`${base} border-dashed border-ink-warn bg-[#3a3220] text-[#e8c96a]`}
      >
        {row.category} ?
      </button>
    );
  }
  return (
    <button
      onClick={onClick}
      className={`${base} border-[#2d5a88] bg-[#22364a] text-[#a9c7e8]`}
    >
      {row.category}
    </button>
  );
}

function Sheet({
  row,
  cats,
  onClose,
  onPick,
}: {
  row: SpendingItem;
  cats: string[];
  onClose: () => void;
  onPick: (c: string) => void;
}) {
  const probs = new Map<string, number>();
  (row.candidates || []).forEach((c) => probs.set(c.category, c.prob));
  const ordered = [...probs.keys(), ...cats.filter((c) => !probs.has(c))];
  return (
    <div className="fixed inset-0 z-[100]">
      <div className="absolute inset-0 bg-black/55" onClick={onClose} />
      <div className="absolute inset-x-0 bottom-0 max-h-[70vh] overflow-y-auto rounded-t-2xl bg-ink-card p-4 pb-6">
        <h2 className="text-sm font-medium">选择类别</h2>
        <div className="mb-3 text-xs text-ink-muted">
          {row.merchant || '（无商户）'} · ¥{yuan(row.amountCents)}
        </div>
        <div className="grid grid-cols-3 gap-2">
          {ordered.map((c) => (
            <button
              key={c}
              onClick={() => onPick(c)}
              className={`rounded-xl border px-1 py-2 text-sm ${
                c === row.category ? 'border-[#2d5a88] bg-[#22364a]' : 'border-ink-line bg-[#22262d]'
              }`}
            >
              {c}
              {probs.has(c) && (
                <span className="block text-[0.65rem] text-ink-muted">
                  {Math.round(probs.get(c)! * 100)}%
                </span>
              )}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function ManualSheet({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [text, setText] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    const t = text.trim();
    if (!t) {
      setErr('请粘贴银行通知文本');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await api.manualEntry(t);
      onDone();
    } catch (e) {
      setErr('录入失败：' + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[100]">
      <div className="absolute inset-0 bg-black/55" onClick={onClose} />
      <div className="absolute inset-x-0 bottom-0 rounded-t-2xl bg-ink-card p-4 pb-6">
        <h2 className="text-sm font-medium">手动录入消费</h2>
        <p className="mb-2 text-xs text-ink-muted">
          粘贴银行通知原文（工行/招行/掌上生活），自动解析金额和商户
        </p>
        <textarea
          rows={4}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="例如：尾号0000卡7月6日11:36支出(消费美团支付-美团App德克士Belray咖啡)36.30元。"
          className="w-full resize-y rounded-lg border border-ink-line bg-ink-base p-2 text-sm"
        />
        {err && <p className="mt-2 text-xs text-red-400">{err}</p>}
        <div className="mt-3 flex gap-2">
          <button
            onClick={submit}
            disabled={busy}
            className="flex-1 rounded-lg bg-ink-warn py-2 text-sm font-semibold text-black disabled:opacity-60"
          >
            {busy ? '解析中…' : '录入'}
          </button>
          <button
            onClick={onClose}
            className="flex-1 rounded-lg border border-ink-line py-2 text-sm"
          >
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
