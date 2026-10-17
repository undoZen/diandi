import { CATEGORIES, type Category } from './classifier';
import type { ReviewRow, Spend, State } from './store';

export const yuan = (cents: number) => {
  const v = cents / 100;
  return Number.isInteger(v) ? `${v}` : v.toFixed(2).replace(/0$/, '');
};

const pad = (n: number) => String(n).padStart(2, '0');
const dayKey = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/** 周一 00:00 为一周起点 */
export function weekStart(t: number): Date {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
}
export const weekKey = (t: number) => dayKey(weekStart(t));
export const monthKey = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
};

export function weekRange(key: string): [number, number] {
  const [y, m, d] = key.split('-').map(Number);
  const start = new Date(y, m - 1, d).getTime();
  return [start, start + 7 * 86_400_000];
}
export function monthRange(key: string): [number, number] {
  const [y, m] = key.split('-').map(Number);
  return [new Date(y, m - 1, 1).getTime(), new Date(y, m, 1).getTime()];
}

export function liveSpends(s: State): Spend[] {
  return s.spends.filter((x) => !x.deleted);
}

export function sumByCategory(spends: Spend[], [from, to]: [number, number]): Record<Category, number> {
  const out: Record<Category, number> = {};
  for (const c of CATEGORIES) out[c] = 0;
  for (const x of spends) if (x.txTime >= from && x.txTime < to) out[x.category] = (out[x.category] ?? 0) + x.amountCents;
  return out;
}

export const total = (r: Record<string, number>) => Object.values(r).reduce((a, b) => a + b, 0);

/** 已有几周真实数据（决定建议预算里真实值的权重——越用越准） */
export function weeksOfData(s: State, before: number): number {
  const keys = new Set(liveSpends(s).filter((x) => x.txTime < before).map((x) => weekKey(x.txTime)));
  return keys.size;
}

const round10 = (cents: number) => Math.max(0, Math.round(cents / 1000) * 1000);

/**
 * 下周建议预算 = 当前预算与本周实际的加权平均；数据越多越信实际（第 1 周 0.5，之后逐步到 0.7）。
 * 用户目标是攒钱/控制时，超支类别再收紧 5%。
 */
export function buildWeekReview(s: State, key: string): ReviewRow[] {
  const [from, to] = weekRange(key);
  const actual = sumByCategory(liveSpends(s), [from, to]);
  const n = weeksOfData(s, to);
  // 周中复盘：按已过天数把实际花费外推到整周，免得周三复盘时把没花到的类别预算砍半
  // 本周中途才开始用的，从开始用的那天算
  const since = Math.max(from, weekStart(s.profile.createdAt).getTime() === from ? new Date(s.profile.createdAt).setHours(0, 0, 0, 0) : from);
  const elapsedDays = Math.min(7, Math.max(1, Math.ceil((Date.now() - since) / 86_400_000)));
  const scale = 7 / elapsedDays;
  const w = Math.min(0.7, 0.4 + 0.1 * n);
  const tighten = /攒|省|控制|存/.test(s.profile.goal);
  return CATEGORIES.map((category) => {
    const budgetCents = s.profile.budgets[category] ?? 0;
    const actualCents = actual[category] ?? 0;
    const projected = actualCents * scale;
    // 还在进行中的一周里一分没花的类别，不据此下调
    // 进行中的一周：天数越少，外推越不可信，真实值权重按已过天数打折；并且最多调到原预算的 2 倍
    const wEff = w * (elapsedDays / 7);
    let suggest = elapsedDays < 7 && actualCents === 0 ? budgetCents : (1 - wEff) * budgetCents + wEff * projected;
    if (budgetCents > 0) suggest = Math.min(suggest, budgetCents * 2);
    if (tighten && actualCents > budgetCents) suggest *= 0.95;
    return { category, actualCents, budgetCents, suggestCents: round10(suggest) };
  }).filter((r) => r.budgetCents > 0 || r.actualCents > 0);
}

export function buildMonthReview(s: State, key: string): ReviewRow[] {
  const [from, to] = monthRange(key);
  const actual = sumByCategory(liveSpends(s), [from, to]);
  const weeks = (to - from) / (7 * 86_400_000);
  return CATEGORIES.map((category) => {
    const weekly = s.profile.budgets[category] ?? 0;
    return {
      category,
      actualCents: actual[category] ?? 0,
      budgetCents: Math.round(weekly * weeks),
      suggestCents: weekly,
    };
  }).filter((r) => r.budgetCents > 0 || r.actualCents > 0);
}

/** 新手默认「猜测」值（元/周），给用户一个起点去改 */
export const DEFAULT_GUESS: Record<string, number> = {
  餐饮: 400, 零食饮料: 100, 交通: 150, 网购: 200, 便利店: 80, 订阅缴费: 100, 医药: 30, 娱乐: 100, 其他: 50,
};

export const CATEGORY_EMOJI: Record<string, string> = {
  餐饮: '🍜', 零食饮料: '🧋', 交通: '🚇', 网购: '📦', 便利店: '🏪', 订阅缴费: '🧾', 医药: '💊', 娱乐: '🎮', 其他: '🧩',
};
