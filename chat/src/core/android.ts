// Android 外壳对接：diandi 的 Ktor 与页面同源（127.0.0.1:PORT），常驻服务抓通知→解析→贝叶斯分类后落 Room，
// 这里轮询 /api/spending 把新消费拉进对话。浏览器/iPhone 里 /api/ping 不通，整个模块静默不工作。
import { CATEGORIES } from './classifier';
import { recordSpend } from './agent';
import { weekKey } from './budget';
import { getState, pushMessage, setState, uid } from './store';

interface RemoteSpend {
  id: number;
  txTime: number;
  amountCents: number;
  merchant: string | null;
  channel: string | null;
  category: string;
  hidden: boolean;
}

export const isAndroidShell = () => /DiandiApp\//.test(navigator.userAgent);

async function syncOnce(): Promise<void> {
  const s = getState();
  if (s.profile.onboardStep !== 'done') return;
  const res = await fetch('/api/spending?limit=100');
  if (!res.ok) return;
  const rows = (await res.json()) as RemoteSpend[];
  const known = new Set(s.spends.map((x) => x.remoteId).filter((x) => x != null));
  const firstSync = s.androidCursor === 0;
  const fresh = rows
    .filter((r) => !r.hidden && r.id > s.androidCursor && !known.has(r.id))
    .sort((a, b) => a.id - b.id);
  // 0 = 从未同步；首次同步后游标至少为 0.5（id 都是正整数），之后的新消费才逐条点评
  if (fresh.length === 0) {
    if (firstSync) setState((st) => ({ ...st, androidCursor: 0.5 }));
    return;
  }
  const thisWeek = weekKey(Date.now());
  for (const r of fresh) {
    // 首次连上：只把本周的历史静默导入，避免一次性刷几十条点评
    if (firstSync && weekKey(r.txTime) !== thisWeek) continue;
    recordSpend(
      { amountCents: r.amountCents, merchant: r.merchant, channel: r.channel, rawText: '' },
      'android',
      {
        txTime: r.txTime, remoteId: r.id,
        category: CATEGORIES.includes(r.category) ? r.category : '其他',
        comment: !firstSync,
      },
    );
  }
  setState((st) => ({ ...st, androidCursor: Math.max(st.androidCursor, ...fresh.map((r) => r.id)) }));
}

export function startAndroidSync(): void {
  if (!isAndroidShell()) return;
  const tick = () => syncOnce().catch(() => { /* 服务重启中，下个周期再试 */ });
  tick();
  checkSetup();
  window.setInterval(tick, 5000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { tick(); checkSetup(); } });
}

interface SetupStatus {
  notifAccess: boolean;
  collectorConnected: boolean;
  batteryIgnored: boolean;
  postNotifs: boolean;
}

let setupPrompted = false;

/** 没开「通知使用权」就抓不到消费：在对话里提醒一次（每次启动最多一次），带去设置页的按钮 */
export async function checkSetup(): Promise<void> {
  if (!isAndroidShell() || setupPrompted || getState().profile.onboardStep !== 'done') return;
  const res = await fetch('/api/setup/status').catch(() => null);
  if (!res?.ok) return;
  const st = (await res.json()) as SetupStatus;
  if (st.notifAccess && st.batteryIgnored) return;
  setupPrompted = true;
  const lines = [
    !st.notifAccess && '· 「通知使用权」还没打开，银行和支付通知进不来，没法自动记账',
    !st.batteryIgnored && '· 还没允许「忽略电池优化」，系统可能会在后台把我关掉',
  ].filter(Boolean).join('\n');
  pushMessage({
    id: uid(), kind: 'text', role: 'assistant', createdAt: Date.now(), persona: getState().profile.persona, local: true,
    text: `要自动记账，还差一步：\n${lines}\n\n点下面的按钮，我带你去打开。`,
    chips: [{ label: '🔐 去开启权限', action: 'native-setup' }, { label: '先不用', action: 'noop' }],
  });
}

export function openNativeSetup(): void {
  location.href = '/native/setup';
}
