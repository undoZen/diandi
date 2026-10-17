// 全部状态存在本机 localStorage，一个 key 一份 JSON；不存在任何服务器。
import { useSyncExternalStore } from 'react';
import type { Category } from './classifier';

export type PersonaId = 'warm' | 'roast';
export type SpendSource = 'manual' | 'demo' | 'android' | 'shortcut';

export interface Spend {
  id: string;
  source: SpendSource;
  /** Android 端 /api/spending 的 id，用于去重与回写纠正 */
  remoteId?: number;
  txTime: number;
  amountCents: number;
  /** 仅本地展示与分类用，永不发给 AI */
  merchant: string | null;
  category: Category;
  confidence: number;
  uncertain: boolean;
  corrected: boolean;
  deleted?: boolean;
}

export interface Chip {
  label: string;
  action: string;
  arg?: string;
}

export type Message =
  | { id: string; kind: 'text'; role: 'user' | 'assistant'; text: string; createdAt: number;
      streaming?: boolean; sent?: string; persona?: PersonaId; chips?: Chip[]; error?: boolean;
      /** 只在本地展示、不进 AI 对话历史（引导脚本、记账短句原文） */
      local?: boolean; spendId?: string }
  | { id: string; kind: 'spend'; spendId: string; createdAt: number }
  | { id: string; kind: 'guess'; createdAt: number; done?: boolean }
  | { id: string; kind: 'review'; createdAt: number; period: 'week' | 'month'; periodKey: string;
      rows: ReviewRow[]; adopted?: boolean };

export interface ReviewRow {
  category: Category;
  actualCents: number;
  budgetCents: number;
  suggestCents: number;
}

export interface Profile {
  onboardStep: 'persona' | 'goal' | 'guess' | 'budget' | 'ai' | 'done';
  persona: PersonaId;
  goal: string;
  /** 用户第一次「猜」的每周各类花费（分） */
  guesses: Record<Category, number>;
  budgets: Record<Category, number>;
  /** 已做过周复盘的周 key（YYYY-MM-DD，周一） */
  reviewedWeeks: string[];
  reviewedMonths: string[];
  createdAt: number;
}

export interface Settings {
  apiKey: string;
  baseUrl: string;
  model: string;
}

export interface State {
  profile: Profile;
  settings: Settings;
  spends: Spend[];
  messages: Message[];
  corrections: { merchant: string; category: Category }[];
  /** Android 已同步到的最大 remoteId */
  androidCursor: number;
}

const KEY = 'diandi-chat-v1';

export const DEFAULT_SETTINGS: Settings = {
  apiKey: '',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
};

function initial(): State {
  return {
    profile: {
      onboardStep: 'persona', persona: 'warm', goal: '', guesses: {}, budgets: {},
      reviewedWeeks: [], reviewedMonths: [], createdAt: Date.now(),
    },
    settings: { ...DEFAULT_SETTINGS },
    spends: [],
    messages: [],
    corrections: [],
    androidCursor: 0,
  };
}

function load(): State {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const s = JSON.parse(raw) as State;
      // 刷新时还在流式输出的消息已经断了
      s.messages = s.messages.map((m) => (m.kind === 'text' && m.streaming ? { ...m, streaming: false } : m));
      return { ...initial(), ...s, settings: { ...DEFAULT_SETTINGS, ...s.settings } };
    }
  } catch { /* 隐私模式等读不到就从头开始 */ }
  return initial();
}

let state: State = load();
const listeners = new Set<() => void>();
let saveTimer: number | undefined;

export function getState(): State {
  return state;
}

export function setState(fn: (s: State) => State): void {
  state = fn(state);
  listeners.forEach((l) => l());
  window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* 写满或被禁用时不影响使用 */ }
  }, 150);
}

export function resetAll(): void {
  try { localStorage.removeItem(KEY); } catch { /* ignore */ }
  state = initial();
  listeners.forEach((l) => l());
}

export function useStore<T>(sel: (s: State) => T): T {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => sel(state),
  );
}

export const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36);

export function pushMessage(m: Message): void {
  setState((s) => ({ ...s, messages: [...s.messages, m] }));
}

export function patchMessage(id: string, patch: Partial<Message>): void {
  setState((s) => ({
    ...s,
    messages: s.messages.map((m) => (m.id === id ? ({ ...m, ...patch } as Message) : m)),
  }));
}

export function patchProfile(patch: Partial<Profile>): void {
  setState((s) => ({ ...s, profile: { ...s.profile, ...patch } }));
}
