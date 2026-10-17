// 对话编排：引导脚本、记账、点评、复盘、自由聊天。UI 只调这里的函数。
import { buildModel, CATEGORIES, classify, type Category } from './classifier';
import {
  buildMonthReview, buildWeekReview, DEFAULT_GUESS, liveSpends, monthKey, sumByCategory, weekKey, weekRange, yuan,
} from './budget';
import { localReview, localSpendComment } from './fallback';
import { streamChat } from './llm';
import { activeConfig, isConfigured } from './providers';
import { parseBankNotification, parseChatEntry, type ParsedSpend } from './parser';
import { parseReceipt } from './receipt';
import { isNativeIOS, latestScreenshot, recognizeFile, recognizeNative, thumbnailOf } from './ocr';
import {
  getState, patchMessage, patchProfile, pushMessage, setState, uid,
  type Chip, type PersonaId, type ReviewRow, type Spend, type SpendSource,
} from './store';
import { PERSONAS } from '../prompts/personas';
import {
  chatPrompt, onboardingSummaryPrompt, reviewPrompt, spendCommentPrompt, type ChatTurn,
} from '../prompts/tasks';

export function init(): void {
  buildModel(getState().corrections);
  const s = getState();
  if (s.messages.length === 0) startOnboarding();
  else if (s.profile.onboardStep === 'done') maybePromptReview();
}

// ───────────────────────── 通用：AI 回复 ─────────────────────────

const say = (text: string, chips?: Chip[], extra: { local?: boolean } = { local: true }) =>
  pushMessage({
    id: uid(), kind: 'text', role: 'assistant', text, createdAt: Date.now(),
    persona: getState().profile.persona, chips, ...extra,
  });

/** 发给 AI 的「非 system」部分，原样展示给用户看（所见即所发） */
const sentPreview = (turns: ChatTurn[]) =>
  turns.filter((t) => t.role !== 'system').map((t) => `[${t.role === 'user' ? '我' : 'AI'}] ${t.content}`).join('\n\n');

async function askAI(turns: ChatTurn[], fallback: () => string, chips?: Chip[]): Promise<void> {
  const s = getState();
  const id = uid();
  const hasKey = isConfigured(s.settings);
  pushMessage({
    id, kind: 'text', role: 'assistant', text: '', createdAt: Date.now(), persona: s.profile.persona,
    streaming: hasKey, sent: hasKey ? sentPreview(turns) : undefined, chips,
  });
  if (!hasKey) {
    patchMessage(id, { text: fallback() });
    return;
  }
  try {
    const text = await streamChat(activeConfig(s.settings), turns, (full) => patchMessage(id, { text: full }));
    patchMessage(id, { text: text || fallback(), streaming: false });
  } catch (e) {
    patchMessage(id, {
      text: `${fallback()}\n\n（AI 暂时连不上，先用本地点评 · ${(e as Error).message}）`,
      streaming: false, error: true,
    });
  }
}

// ───────────────────────── 引导 ─────────────────────────

const GOALS = ['每月攒下一笔钱', '控制吃喝和网购', '先搞清楚钱都去哪了', '还清信用卡'];

function startOnboarding(): void {
  say('你好，这里是品质记账 👋 少花钱，但品质还是必须。我是陪你记账的 AI，帮你把钱花在值得的地方。\n\n你的流水只存在这台设备上，AI 只会知道「哪一类花了多少钱」。\n\n先选一个陪你的伙伴吧（之后随时能换）：', [
    { label: `${PERSONAS.warm.avatar} ${PERSONAS.warm.name} · ${PERSONAS.warm.tagline}`, action: 'persona', arg: 'warm' },
    { label: `${PERSONAS.roast.avatar} ${PERSONAS.roast.name} · ${PERSONAS.roast.tagline}`, action: 'persona', arg: 'roast' },
  ]);
}

export function choosePersona(id: PersonaId): void {
  const step = getState().profile.onboardStep;
  patchProfile({ persona: id });
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: `${PERSONAS[id].avatar} ${PERSONAS[id].name}`, createdAt: Date.now(), local: true });
  if (step !== 'persona') {
    say(id === 'warm' ? '换我来陪你啦 🌷 接下来温柔一点～' : '辣姐上线。准备好被骂醒了吗？🌶️');
    return;
  }
  patchProfile({ onboardStep: 'goal' });
  say(id === 'warm'
    ? '好呀，我是小暖 🌷 你最想通过记账达成什么目标？选一个，或者直接打字告诉我。'
    : '我是辣姐。先说清楚，你记账到底想干嘛？选一个，或者自己打字。',
  GOALS.map((g) => ({ label: g, action: 'goal', arg: g })));
}

export function chooseGoal(goal: string): void {
  patchProfile({ goal, onboardStep: 'guess' });
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: goal, createdAt: Date.now() });
  say(getState().profile.persona === 'warm'
    ? '收到！现在还没有数据，我们先来玩个小游戏：猜一猜你**每周**在各类上大概花多少钱？凭感觉填就好，猜错也没关系，之后每周复盘会帮你校准 ✨'
    : '现在你一笔账都没有，我也没法骂你。先猜：你觉得自己**每周**在这些上面各花多少？别装，凭感觉填。');
  pushMessage({ id: uid(), kind: 'guess', createdAt: Date.now() });
}

export function submitGuess(msgId: string, guesses: Record<Category, number>): void {
  patchMessage(msgId, { done: true });
  const totalCents = Object.values(guesses).reduce((a, b) => a + b, 0);
  patchProfile({ guesses, onboardStep: 'budget' });
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: `我猜每周大概花 ${yuan(totalCents)} 元`, createdAt: Date.now(), local: true });
  say(`好，每周大约 ${yuan(totalCents)} 元。要把这个数直接当成第一周的预算，还是给自己加点挑战？`, [
    { label: '就按猜的来', action: 'budget', arg: '1' },
    { label: '挑战：少花 10%', action: 'budget', arg: '0.9' },
  ]);
}

export function chooseBudget(factor: number): void {
  const { guesses } = getState().profile;
  const budgets: Record<Category, number> = {};
  for (const [c, v] of Object.entries(guesses)) budgets[c] = Math.round((v * factor) / 1000) * 1000;
  patchProfile({ budgets, onboardStep: 'ai' });
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: factor < 1 ? '挑战：少花 10%' : '就按猜的来', createdAt: Date.now(), local: true });
  say('最后一步：接入 AI 点评。可以选 DeepSeek、智谱 GLM、Kimi，或者任何 OpenAI 兼容接口，填上自己的 API Key。Key 只保存在本机，请求从你的设备直接发给 AI，不经过任何中转服务器。\n\n也可以先跳过，用本地点评体验一下。', [
    { label: '去填 API Key', action: 'settings' },
    { label: '先跳过', action: 'finish-onboarding' },
  ]);
}

export function finishOnboarding(): void {
  if (getState().profile.onboardStep === 'done') return;
  patchProfile({ onboardStep: 'done', reviewedWeeks: [weekKey(Date.now())] });
  const s = getState();
  askAI(onboardingSummaryPrompt(s), () => (s.profile.persona === 'warm'
    ? `准备好啦！目标是「${s.profile.goal}」，每周预算 ${yuan(Object.values(s.profile.budgets).reduce((a, b) => a + b, 0))} 元。以后有消费直接发「午饭 35」这样一句就能记，安卓上会自动从通知里抓。每周我们一起复盘 🌷`
    : `行，目标「${s.profile.goal}」，记住了。以后花一笔就发「午饭 35」，安卓会自动抓通知。每周一我来跟你算总账。`), [
    { label: '试试：午饭 35', action: 'send', arg: '午饭 35' },
    { label: '模拟一条银行通知', action: 'demo' },
    { label: '📸 用支付截图记一笔', action: 'screenshot' },
  ]);
}

// ───────────────────────── 记账 ─────────────────────────

export function recordSpend(p: ParsedSpend, source: SpendSource, opts: { txTime?: number; remoteId?: number; category?: Category; comment?: boolean } = {}): Spend {
  const pred = opts.category ? { category: opts.category, confidence: 1, uncertain: false } : classify(p.merchant);
  const spend: Spend = {
    id: uid(), source, remoteId: opts.remoteId, txTime: opts.txTime ?? Date.now(),
    amountCents: p.amountCents, merchant: p.merchant, category: pred.category,
    confidence: pred.confidence, uncertain: pred.uncertain, corrected: false,
  };
  setState((s) => ({ ...s, spends: [...s.spends, spend] }));
  pushMessage({ id: uid(), kind: 'spend', spendId: spend.id, createdAt: Date.now() });
  if (opts.comment !== false) commentOnSpend(spend);
  return spend;
}

function commentOnSpend(spend: Spend): void {
  const s = getState();
  const spent = sumByCategory(liveSpends(s), weekRange(weekKey(spend.txTime)))[spend.category] ?? 0;
  const budget = s.profile.budgets[spend.category] ?? 0;
  askAI(spendCommentPrompt(s, spend.category, spend.amountCents),
    () => localSpendComment(s.profile.persona, spend.category, spend.amountCents, spent, budget));
}

export function correctCategory(spendId: string, category: Category): void {
  const spend = getState().spends.find((x) => x.id === spendId);
  if (!spend || spend.category === category) return;
  setState((s) => ({
    ...s,
    spends: s.spends.map((x) => (x.id === spendId ? { ...x, category, corrected: true, uncertain: false, confidence: 1 } : x)),
    corrections: spend.merchant ? [...s.corrections, { merchant: spend.merchant, category }] : s.corrections,
  }));
  buildModel(getState().corrections);
  if (spend.remoteId != null) {
    fetch(`/api/spending/${spend.remoteId}/category`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ category }),
    }).catch(() => { /* 不在 Android 内或服务未起，本地已改 */ });
  }
}

export function deleteSpend(spendId: string): void {
  setState((s) => ({ ...s, spends: s.spends.map((x) => (x.id === spendId ? { ...x, deleted: true } : x)) }));
}

// ───────────────────────── 用户输入 ─────────────────────────

export function handleUserText(text: string): void {
  const t = text.trim();
  if (!t) return;
  const s = getState();
  if (s.profile.onboardStep === 'goal') return chooseGoal(t);
  if (s.profile.onboardStep !== 'done' && s.profile.onboardStep !== 'ai') {
    pushMessage({ id: uid(), kind: 'text', role: 'user', text: t, createdAt: Date.now(), local: true });
    say('先把上面这一步完成吧，点按钮就行～');
    return;
  }
  if (s.profile.onboardStep === 'ai') finishOnboarding();
  if (/^(周报|本周复盘|复盘)$/.test(t)) return runWeekReview(weekKey(Date.now()));
  if (/^(月报|月度复盘)$/.test(t)) return runMonthReview(monthKey(Date.now()));

  const bank = parseBankNotification(t);
  const entry = bank ?? parseChatEntry(t);
  if (entry) {
    pushMessage({ id: uid(), kind: 'text', role: 'user', text: t, createdAt: Date.now(), local: true });
    recordSpend(entry, 'manual');
    return;
  }
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: t, createdAt: Date.now() });
  const after = getState();
  // 历史：最近 12 条可发送的文字消息；记账短句只以「类别 金额」出现
  const history: ChatTurn[] = [];
  for (const m of after.messages.slice(-30)) {
    if (m.kind === 'text' && !m.local && !m.error && m.text) history.push({ role: m.role, content: m.text });
    if (m.kind === 'spend') {
      const sp = after.spends.find((x) => x.id === m.spendId);
      if (sp && !sp.deleted) history.push({ role: 'user', content: `（记了一笔：${sp.category} ${yuan(sp.amountCents)} 元）` });
    }
  }
  askAI(chatPrompt(after, history.slice(-12)), () => '我现在还没接上 AI（菜单 → 设置 里填 API Key）。不过记账没问题，直接发「午饭 35」试试～');
}

// ───────────────────────── 复盘 ─────────────────────────

function reviewText(rows: ReviewRow[]) {
  const over = rows.filter((r) => r.budgetCents > 0 && r.actualCents > r.budgetCents).map((r) => r.category);
  const under = rows.filter((r) => r.actualCents > 0 && r.actualCents <= r.budgetCents).map((r) => r.category);
  return localReview(getState().profile.persona, over, under);
}

export function runWeekReview(key: string): void {
  const s = getState();
  const rows = buildWeekReview(s, key);
  const label = key === weekKey(Date.now()) ? '本周（进行中）' : `${key} 这一周`;
  pushMessage({ id: uid(), kind: 'review', createdAt: Date.now(), period: 'week', periodKey: key, rows });
  patchProfile({ reviewedWeeks: [...new Set([...s.profile.reviewedWeeks, key])] });
  askAI(reviewPrompt(s, 'week', label, rows), () => reviewText(rows));
}

export function runMonthReview(key: string): void {
  const s = getState();
  const rows = buildMonthReview(s, key);
  pushMessage({ id: uid(), kind: 'review', createdAt: Date.now(), period: 'month', periodKey: key, rows });
  patchProfile({ reviewedMonths: [...new Set([...s.profile.reviewedMonths, key])] });
  askAI(reviewPrompt(s, 'month', `${key} 月度`, rows), () => reviewText(rows));
}

export function adoptReview(msgId: string, rows: ReviewRow[]): void {
  const budgets = { ...getState().profile.budgets };
  for (const r of rows) budgets[r.category] = r.suggestCents;
  patchProfile({ budgets });
  patchMessage(msgId, { adopted: true });
  say('新一周的预算已更新 ✅');
}

/** 新一周第一次打开：提示复盘上周；新月份第一次打开：提示月复盘 */
function maybePromptReview(): void {
  const s = getState();
  const now = Date.now();
  const lastWeek = weekKey(now - 7 * 86_400_000);
  const hasLastWeekData = liveSpends(s).some((x) => weekKey(x.txTime) === lastWeek);
  if (hasLastWeekData && !s.profile.reviewedWeeks.includes(lastWeek)) {
    say('新的一周开始啦，要不要先花一分钟复盘上周？', [
      { label: '复盘上周', action: 'review-week', arg: lastWeek },
      { label: '晚点再说', action: 'dismiss-review', arg: lastWeek },
    ]);
  }
  const lastMonthD = new Date(now); lastMonthD.setDate(0);
  const lastMonth = monthKey(lastMonthD.getTime());
  if (liveSpends(s).some((x) => monthKey(x.txTime) === lastMonth) && !s.profile.reviewedMonths.includes(lastMonth)) {
    say(`${lastMonth} 已经结束了，来个月度复盘？`, [{ label: '月度复盘', action: 'review-month', arg: lastMonth }]);
  }
}

export function dismissReview(key: string): void {
  patchProfile({ reviewedWeeks: [...new Set([...getState().profile.reviewedWeeks, key])] });
}

// ───────────────────────── 演示 & 外部来源 ─────────────────────────

const DEMO_NOTIFICATIONS = [
  '您账户0000于{d}在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币19.90',
  '您在美团-美团特约商户有一笔38.50人民币的消费已成功，点击查看详情',
  '您在支付宝-上海都畅数字技术有限公司有一笔4.00人民币的消费已成功，点击查看详情',
  '您在财付通-上海全家便利店有一笔13.10人民币的消费已成功，点击查看详情',
  '尾号0000卡{d}支出(消费联通支付-中国联通北京分公司手机缴费)50元。',
  '您在支付宝-浙江天猫供应链管理有限公司有一笔129.00人民币的消费已成功，点击查看详情',
  '您账户0000于{d}在【财付通-微信支付-星巴克】发生快捷支付扣款，人民币36.00',
  '您在京东支付-京东商城平台商户有一笔259.00人民币的消费已成功，点击查看详情',
];

/** 演示用：随机一条真实格式的银行通知，走与 Android 采集完全相同的解析与分类 */
export function injectDemoNotification(): void {
  const now = new Date();
  const d = `${String(now.getMonth() + 1).padStart(2, '0')}月${String(now.getDate()).padStart(2, '0')}日${now.getHours()}:${String(now.getMinutes()).padStart(2, '0')}`;
  const raw = DEMO_NOTIFICATIONS[Math.floor(Math.random() * DEMO_NOTIFICATIONS.length)].replace('{d}', d);
  const p = parseBankNotification(raw);
  if (!p) return;
  pushMessage({ id: uid(), kind: 'text', role: 'user', text: `🔔 收到银行通知：${raw}`, createdAt: Date.now(), local: true });
  recordSpend(p, 'demo');
}

/** iOS 快捷指令 / 外部链接：?spend=<通知原文或「午饭 35」> */
export function consumeUrlSpend(): void {
  const url = new URL(location.href);
  const text = url.searchParams.get('spend');
  if (!text) return;
  url.searchParams.delete('spend');
  history.replaceState(null, '', url.toString());
  if (getState().profile.onboardStep !== 'done') return;
  const p = parseBankNotification(text) ?? parseChatEntry(text);
  if (p) recordSpend(p, 'shortcut');
}

export { CATEGORIES };

// ───────────────────────── 截图记账 ─────────────────────────

const pendingFiles = new Map<string, Blob>();

function recordFromLines(msgId: string, lines: string[]): void {
  const r = parseReceipt(lines);
  if (!r) {
    patchMessage(msgId, { state: 'failed' });
    say('这张截图里没认出消费金额（可能是退款、收款或者不是支付页）。可以直接打字记，比如「午饭 35」。');
    return;
  }
  patchMessage(msgId, { state: 'done' });
  recordSpend({ amountCents: r.amountCents, merchant: r.merchant, channel: null, rawText: r.rawText }, 'screenshot',
    { txTime: r.txTime && Math.abs(Date.now() - r.txTime) < 30 * 86_400_000 ? r.txTime : Date.now() });
}

/** iOS：打开/回到 App 时看 1 分钟内有没有新截图，有就问一句 */
export async function checkRecentScreenshot(): Promise<void> {
  if (!isNativeIOS() || getState().profile.onboardStep !== 'done') return;
  const info = await latestScreenshot(60).catch(() => ({ found: false } as const));
  if (!info.found || !info.id || getState().seenScreenshots.includes(info.id)) return;
  setState((s) => ({ ...s, seenScreenshots: [...s.seenScreenshots.slice(-50), info.id!] }));
  pushMessage({ id: uid(), kind: 'screenshot', createdAt: Date.now(), thumbnail: info.thumbnail, nativeId: info.id, state: 'ask' });
}

export async function applyScreenshot(msgId: string): Promise<void> {
  const msg = getState().messages.find((m) => m.id === msgId);
  if (!msg || msg.kind !== 'screenshot') return;
  patchMessage(msgId, { state: 'reading', progress: 0 });
  try {
    const file = pendingFiles.get(msgId);
    const lines = msg.nativeId
      ? await recognizeNative(msg.nativeId)
      : file ? await recognizeFile(file, (p) => patchMessage(msgId, { progress: p })) : [];
    pendingFiles.delete(msgId);
    recordFromLines(msgId, lines);
  } catch (e) {
    patchMessage(msgId, { state: 'failed' });
    say(`识别失败了（${(e as Error).message}），可以直接打字记一笔。`);
  }
}

export function dismissScreenshot(msgId: string): void {
  pendingFiles.delete(msgId);
  patchMessage(msgId, { state: 'dismissed' });
}

/** 浏览器/演示：用户主动选了一张截图，直接识别 */
export async function addScreenshotFile(file: Blob): Promise<void> {
  const id = uid();
  pendingFiles.set(id, file);
  const thumbnail = await thumbnailOf(file).catch(() => undefined);
  pushMessage({ id, kind: 'screenshot', createdAt: Date.now(), thumbnail, state: 'ask' });
  if (getState().profile.onboardStep === 'ai') finishOnboarding();
  applyScreenshot(id);
}
