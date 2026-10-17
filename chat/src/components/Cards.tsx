import { useState } from 'react';
import { adoptReview, CATEGORIES, correctCategory, deleteSpend, submitGuess } from '../core/agent';
import { CATEGORY_EMOJI, DEFAULT_GUESS, sumByCategory, weekKey, weekRange, yuan } from '../core/budget';
import { useStore, type Message, type ReviewRow } from '../core/store';

const SOURCE_LABEL = { manual: '手记', demo: '模拟通知', android: '通知自动采集', shortcut: '快捷指令' } as const;

export function ProgressBar({ spent, budget }: { spent: number; budget: number }) {
  const ratio = budget > 0 ? spent / budget : spent > 0 ? 1.01 : 0;
  const color = ratio > 1 ? 'bg-roast' : ratio > 0.8 ? 'bg-amber-500' : 'bg-leaf';
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-line">
      <div className={`h-full rounded-full ${color}`} style={{ width: `${Math.min(100, ratio * 100)}%` }} />
    </div>
  );
}

export function SpendCard({ spendId }: { spendId: string }) {
  const spend = useStore((s) => s.spends.find((x) => x.id === spendId));
  const spends = useStore((s) => s.spends);
  const budgets = useStore((s) => s.profile.budgets);
  const [picking, setPicking] = useState(false);
  if (!spend) return null;
  if (spend.deleted) {
    return <div className="mx-auto my-1 text-center text-xs text-muted">已撤销一笔 {spend.category} {yuan(spend.amountCents)} 元</div>;
  }
  const spent = sumByCategory(spends.filter((x) => !x.deleted), weekRange(weekKey(spend.txTime)))[spend.category] ?? 0;
  const budget = budgets[spend.category] ?? 0;
  return (
    <div className="mx-auto w-full max-w-sm rounded-2xl border border-line bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between">
        <div>
          <div className="text-xs text-muted">{SOURCE_LABEL[spend.source]} · {new Date(spend.txTime).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</div>
          <div className="mt-1 text-2xl font-semibold tabular-nums">¥{yuan(spend.amountCents)}</div>
          {spend.merchant && <div className="mt-0.5 max-w-[14rem] truncate text-xs text-muted" title="仅存本机，不发送给 AI">🔒 {spend.merchant}</div>}
        </div>
        <button
          onClick={() => setPicking(!picking)}
          className={`rounded-full border px-3 py-1 text-sm ${spend.uncertain ? 'border-amber-400 bg-amber-50' : 'border-line bg-paper'}`}
        >
          {CATEGORY_EMOJI[spend.category]} {spend.category}{spend.uncertain ? ' ?' : ''} ▾
        </button>
      </div>
      {picking && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {CATEGORIES.map((c) => (
            <button key={c} onClick={() => { correctCategory(spend.id, c); setPicking(false); }}
              className={`rounded-full px-2.5 py-1 text-xs ${c === spend.category ? 'bg-ink text-white' : 'bg-paper'}`}>
              {CATEGORY_EMOJI[c]} {c}
            </button>
          ))}
          <button onClick={() => deleteSpend(spend.id)} className="rounded-full px-2.5 py-1 text-xs text-roast">撤销这笔</button>
        </div>
      )}
      <div className="mt-3">
        <div className="mb-1 flex justify-between text-xs text-muted">
          <span>本周{spend.category}</span>
          <span className="tabular-nums">{yuan(spent)} / {budget ? yuan(budget) : '未设预算'}</span>
        </div>
        <ProgressBar spent={spent} budget={budget} />
      </div>
      {spend.corrected && <div className="mt-2 text-[11px] text-leaf">已纠正，分类器记住啦（本机训练）</div>}
    </div>
  );
}

export function GuessCard({ msg }: { msg: Extract<Message, { kind: 'guess' }> }) {
  const [vals, setVals] = useState<Record<string, string>>(
    Object.fromEntries(CATEGORIES.map((c) => [c, String(DEFAULT_GUESS[c] ?? 0)])),
  );
  const sum = Object.values(vals).reduce((a, v) => a + (Number(v) || 0), 0);
  return (
    <div className="mx-auto w-full max-w-sm rounded-2xl border border-line bg-white p-4 shadow-sm">
      <div className="mb-3 text-sm font-medium">猜一猜：你每周在这些上面花多少？（元）</div>
      <div className="grid grid-cols-2 gap-2">
        {CATEGORIES.map((c) => (
          <label key={c} className="flex items-center justify-between rounded-xl bg-paper px-3 py-2 text-sm">
            <span>{CATEGORY_EMOJI[c]} {c}</span>
            <input
              type="number" inputMode="decimal" disabled={msg.done}
              value={vals[c]} onChange={(e) => setVals({ ...vals, [c]: e.target.value })}
              className="w-14 bg-transparent text-right tabular-nums outline-none"
            />
          </label>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-between">
        <span className="text-sm text-muted">合计 ¥{sum}/周</span>
        <button
          disabled={msg.done}
          onClick={() => submitGuess(msg.id, Object.fromEntries(CATEGORIES.map((c) => [c, Math.round((Number(vals[c]) || 0) * 100)])))}
          className="rounded-full bg-ink px-4 py-1.5 text-sm text-white disabled:opacity-40"
        >
          {msg.done ? '已提交' : '就这么猜'}
        </button>
      </div>
    </div>
  );
}

export function ReviewCard({ msg }: { msg: Extract<Message, { kind: 'review' }> }) {
  const isWeek = msg.period === 'week';
  const sumA = msg.rows.reduce((a, r) => a + r.actualCents, 0);
  const sumB = msg.rows.reduce((a, r) => a + r.budgetCents, 0);
  return (
    <div className="mx-auto w-full max-w-sm rounded-2xl border border-line bg-white p-4 shadow-sm">
      <div className="flex items-baseline justify-between">
        <div className="text-sm font-medium">{isWeek ? `📅 周复盘 · ${msg.periodKey} 起` : `🗓️ 月复盘 · ${msg.periodKey}`}</div>
        <div className="text-xs tabular-nums text-muted">{yuan(sumA)} / {yuan(sumB)}</div>
      </div>
      <div className="mt-3 space-y-2.5">
        {msg.rows.map((r: ReviewRow) => (
          <div key={r.category}>
            <div className="mb-1 flex justify-between text-xs">
              <span>{CATEGORY_EMOJI[r.category]} {r.category}</span>
              <span className="tabular-nums text-muted">
                {yuan(r.actualCents)} / {yuan(r.budgetCents)}
                {isWeek && r.suggestCents !== r.budgetCents && (
                  <span className="ml-1 text-ink">→ {yuan(r.suggestCents)}</span>
                )}
              </span>
            </div>
            <ProgressBar spent={r.actualCents} budget={r.budgetCents} />
          </div>
        ))}
        {msg.rows.length === 0 && <div className="text-sm text-muted">这段时间还没有记账数据。</div>}
      </div>
      {isWeek && msg.rows.length > 0 && (
        <button
          disabled={msg.adopted}
          onClick={() => adoptReview(msg.id, msg.rows)}
          className="mt-4 w-full rounded-full bg-ink py-2 text-sm text-white disabled:opacity-40"
        >
          {msg.adopted ? '已采纳' : '采纳建议预算（→ 后的数字）'}
        </button>
      )}
    </div>
  );
}

export function WeekSummary() {
  const spends = useStore((s) => s.spends);
  const budgets = useStore((s) => s.profile.budgets);
  const spent = sumByCategory(spends.filter((x) => !x.deleted), weekRange(weekKey(Date.now())));
  const totalSpent = Object.values(spent).reduce((a, b) => a + b, 0);
  const totalBudget = Object.values(budgets).reduce((a, b) => a + b, 0);
  return (
    <div className="w-full">
      <div className="mb-1 flex justify-between text-[11px] text-muted">
        <span>本周</span>
        <span className="tabular-nums">¥{yuan(totalSpent)} / {yuan(totalBudget)}</span>
      </div>
      <ProgressBar spent={totalSpent} budget={totalBudget} />
    </div>
  );
}

