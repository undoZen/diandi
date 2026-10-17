import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  addScreenshotFile, CATEGORIES, chooseBudget, chooseGoal, choosePersona, dismissReview, finishOnboarding, handleUserText,
  injectDemoNotification, runMonthReview, runWeekReview,
} from './core/agent';
import { isAndroidShell, openNativeSetup } from './core/android';
import { CATEGORY_EMOJI, monthKey, weekKey, yuan } from './core/budget';
import { patchProfile, resetAll, setState, useStore, type Chip, type Message } from './core/store';
import { PERSONAS } from './prompts/personas';
import { GuessCard, ReviewCard, ScreenshotCard, SpendCard, WeekSummary } from './components/Cards';
import { isNativeIOS } from './core/ocr';
import { activeConfig, isConfigured, PROVIDERS, providerOf } from './core/providers';
import { testConnection } from './core/llm';

export const COMMUNITY_URL = 'https://github.com/undoZen/diandi/issues';

/** 极简富文本：**粗体** 与换行 */
function RichText({ text }: { text: string }) {
  return (
    <>
      {text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
        part.startsWith('**') && part.endsWith('**') ? <b key={i}>{part.slice(2, -2)}</b> : <span key={i}>{part}</span>,
      )}
    </>
  );
}

let fileInput: HTMLInputElement | null = null;
function pickScreenshot() {
  fileInput?.click();
}

function runChip(c: Chip, openSettings: () => void) {
  switch (c.action) {
    case 'persona': return choosePersona(c.arg as 'warm' | 'roast');
    case 'goal': return chooseGoal(c.arg!);
    case 'budget': return chooseBudget(Number(c.arg));
    case 'settings': return openSettings();
    case 'finish-onboarding': return finishOnboarding();
    case 'send': return handleUserText(c.arg!);
    case 'demo': return injectDemoNotification();
    case 'screenshot': return pickScreenshot();
    case 'native-setup': return openNativeSetup();
    case 'noop': return;
    case 'review-week': return runWeekReview(c.arg!);
    case 'review-month': return runMonthReview(c.arg!);
    case 'dismiss-review': return dismissReview(c.arg!);
  }
}

function TextBubble({ m, isLast, openSettings }: { m: Extract<Message, { kind: 'text' }>; isLast: boolean; openSettings: () => void }) {
  const [showSent, setShowSent] = useState(false);
  const [used, setUsed] = useState(false);
  if (m.role === 'user') {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-ink px-3.5 py-2 text-[15px] text-white">{m.text}</div>
      </div>
    );
  }
  const p = PERSONAS[m.persona ?? 'warm'];
  return (
    <div className="flex gap-2">
      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white text-lg shadow-sm">{p.avatar}</div>
      <div className="min-w-0 max-w-[85%]">
        <div className={`whitespace-pre-wrap break-words rounded-2xl rounded-tl-md bg-white px-3.5 py-2 text-[15px] leading-relaxed shadow-sm ${m.streaming ? 'caret' : ''}`}>
          {m.text ? <RichText text={m.text} /> : <span className="text-muted">思考中…</span>}
        </div>
        {m.sent && (
          <button onClick={() => setShowSent(!showSent)} className="mt-1 text-[11px] text-muted">
            🔒 本次发给 AI 的内容 {showSent ? '▴' : '▾'}
          </button>
        )}
        {showSent && <pre className="mt-1 whitespace-pre-wrap break-words rounded-xl bg-line/60 p-2 text-[11px] leading-snug text-muted">{m.sent}</pre>}
        {m.chips && isLast && !used && (
          <div className="mt-2 flex flex-wrap gap-2">
            {m.chips.map((c) => (
              <button key={c.label} onClick={() => { setUsed(true); runChip(c, openSettings); }}
                className="rounded-full border border-ink/15 bg-white px-3 py-1.5 text-sm shadow-sm active:bg-paper">
                {c.label}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-20 flex items-end justify-center bg-black/30" onClick={onClose}>
      <div className="max-h-[85vh] w-full max-w-md overflow-y-auto rounded-t-3xl bg-paper p-5 pb-8" onClick={(e) => e.stopPropagation()}>
        <div className="mb-4 flex items-center justify-between">
          <div className="text-lg font-semibold">{title}</div>
          <button onClick={onClose} className="text-muted">关闭</button>
        </div>
        {children}
      </div>
    </div>
  );
}

function SettingsSheet({ onClose }: { onClose: () => void }) {
  const settings = useStore((s) => s.settings);
  const step = useStore((s) => s.profile.onboardStep);
  const [draft, setDraft] = useState(settings);
  const [test, setTest] = useState<{ state: 'idle' | 'running' | 'ok' | 'fail'; text?: string }>({ state: 'idle' });
  const prov = providerOf(draft.provider);
  const cfg = activeConfig(draft);
  const webBlocked = !prov.browserDirect && !isNativeIOS() && !isAndroidShell();

  const save = () => {
    setState((s) => ({ ...s, settings: draft }));
    onClose();
    if (step === 'ai') finishOnboarding();
  };
  const runTest = async () => {
    setTest({ state: 'running' });
    try {
      setTest({ state: 'ok', text: await testConnection(cfg) });
    } catch (e) {
      setTest({ state: 'fail', text: (e as Error).message });
    }
  };
  const input = 'w-full rounded-xl border border-line bg-white px-3 py-2 text-sm outline-none focus:border-ink/40';

  return (
    <Sheet title="AI 设置" onClose={onClose}>
      <div className="mb-4 grid grid-cols-4 gap-1.5 rounded-2xl bg-line/60 p-1">
        {PROVIDERS.map((p) => (
          <button key={p.id} onClick={() => { setDraft({ ...draft, provider: p.id }); setTest({ state: 'idle' }); }}
            className={`rounded-xl py-2 text-sm ${draft.provider === p.id ? 'bg-white font-medium shadow-sm' : 'text-muted'}`}>
            {p.name}
          </button>
        ))}
      </div>

      <div className="mb-3 rounded-xl bg-white px-3 py-2.5 text-xs leading-relaxed text-muted">
        {prov.keyUrl
          ? <>还没有 Key？{prov.keyHint}：<a href={prov.keyUrl} target="_blank" rel="noreferrer" className="text-leaf underline">{prov.keyUrl.replace('https://', '')}</a></>
          : prov.keyHint}
        {webBlocked && <div className="mt-1.5 text-roast">{prov.name} 的接口不允许网页直接调用，网页版里用不了；在 iPhone / Android App 里可以正常使用。</div>}
      </div>

      {draft.provider === 'custom' && (
        <label className="mb-3 block">
          <div className="mb-1 text-sm text-muted">接口地址</div>
          <input value={draft.customBaseUrl} placeholder="https://example.com/v1" className={input}
            onChange={(e) => setDraft({ ...draft, customBaseUrl: e.target.value })} />
        </label>
      )}
      <label className="mb-3 block">
        <div className="mb-1 text-sm text-muted">API Key（只存本机）</div>
        <input type="password" value={draft.keys[draft.provider] ?? ''} placeholder="sk-…" className={input}
          onChange={(e) => setDraft({ ...draft, keys: { ...draft.keys, [draft.provider]: e.target.value } })} />
      </label>
      <label className="mb-3 block">
        <div className="mb-1 text-sm text-muted">模型</div>
        <input value={draft.models[draft.provider] ?? ''} placeholder={prov.defaultModel || '模型名'} list={`models-${prov.id}`} className={input}
          onChange={(e) => setDraft({ ...draft, models: { ...draft.models, [draft.provider]: e.target.value } })} />
        <datalist id={`models-${prov.id}`}>{prov.models.map((m) => <option key={m} value={m} />)}</datalist>
      </label>

      <div className="mb-4 flex items-center gap-3">
        <button onClick={runTest} disabled={!isConfigured(draft) || test.state === 'running'}
          className="shrink-0 rounded-full border border-ink/20 bg-white px-4 py-1.5 text-sm disabled:opacity-40">
          {test.state === 'running' ? '测试中…' : '测试连接'}
        </button>
        {test.state === 'ok' && <span className="truncate text-xs text-leaf">✅ {test.text}</span>}
        {test.state === 'fail' && <span className="text-xs text-roast">❌ {test.text}</span>}
      </div>

      <p className="mb-4 text-xs leading-relaxed text-muted">
        请求从你的设备直接发给 {prov.name === '自定义' ? '你填的接口' : prov.name}，不经过任何中转服务器。AI 只会收到消费类别、金额和预算进度，
        不会收到商户名、截图和通知原文；每条回复下的「🔒 本次发给 AI 的内容」可以核对。
      </p>
      <button onClick={save} className="w-full rounded-full bg-ink py-2.5 text-white">保存</button>
    </Sheet>
  );
}

function BudgetSheet({ onClose }: { onClose: () => void }) {
  const budgets = useStore((s) => s.profile.budgets);
  const [vals, setVals] = useState<Record<string, string>>(
    Object.fromEntries(CATEGORIES.map((c) => [c, yuan(budgets[c] ?? 0)])),
  );
  const save = () => {
    patchProfile({ budgets: Object.fromEntries(CATEGORIES.map((c) => [c, Math.round((Number(vals[c]) || 0) * 100)])) });
    onClose();
  };
  return (
    <Sheet title="每周预算（元）" onClose={onClose}>
      <div className="grid grid-cols-2 gap-2">
        {CATEGORIES.map((c) => (
          <label key={c} className="flex items-center justify-between rounded-xl bg-white px-3 py-2 text-sm">
            <span>{CATEGORY_EMOJI[c]} {c}</span>
            <input type="number" inputMode="decimal" value={vals[c]} onChange={(e) => setVals({ ...vals, [c]: e.target.value })}
              className="w-16 bg-transparent text-right tabular-nums outline-none" />
          </label>
        ))}
      </div>
      <button onClick={save} className="mt-4 w-full rounded-full bg-ink py-2.5 text-white">保存</button>
    </Sheet>
  );
}

function Menu({ onClose, openSettings, openBudget }: { onClose: () => void; openSettings: () => void; openBudget: () => void }) {
  const persona = useStore((s) => s.profile.persona);
  const other = persona === 'warm' ? 'roast' : 'warm';
  const item = (icon: string, label: string, fn: () => void) => (
    <button onClick={() => { onClose(); fn(); }} className="flex w-full items-center gap-3 rounded-xl bg-white px-4 py-3 text-left">
      <span className="text-xl">{icon}</span><span>{label}</span>
    </button>
  );
  return (
    <Sheet title="菜单" onClose={onClose}>
      <div className="space-y-2">
        {item('📸', '截图记账（支付成功页 / 账单详情）', pickScreenshot)}
        {item('🔔', '模拟一条银行消费通知', injectDemoNotification)}
        {item('📅', '本周复盘', () => runWeekReview(weekKey(Date.now())))}
        {item('🗓️', '本月复盘', () => runMonthReview(monthKey(Date.now())))}
        {item('🎯', '调整每周预算', openBudget)}
        {item(PERSONAS[other].avatar, `换成「${PERSONAS[other].name}」· ${PERSONAS[other].tagline}`, () => choosePersona(other))}
        {isAndroidShell() && item('🔐', '权限与保活设置（自动记账必看）', openNativeSetup)}
        {item('⚙️', 'AI 设置', openSettings)}
        {item('💬', '社区：和大家聊聊记账与保障', () => window.open(COMMUNITY_URL, '_blank'))}
        {item('🧹', '清空本机数据重新开始', () => { if (confirm('确定清空本机所有记账与对话？')) { resetAll(); location.reload(); } })}
      </div>
      <p className="mt-4 text-center text-xs text-muted">{isAndroidShell() ? '已连接本机通知采集' : isNativeIOS() ? '截图后打开 App 即可自动识别' : '数据仅存于本浏览器'} · 必须品质</p>
    </Sheet>
  );
}

export default function App() {
  const messages = useStore((s) => s.messages);
  const persona = useStore((s) => s.profile.persona);
  const step = useStore((s) => s.profile.onboardStep);
  const [input, setInput] = useState('');
  const [sheet, setSheet] = useState<null | 'menu' | 'settings' | 'budget'>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const last = messages[messages.length - 1];
  const lastText = last?.kind === 'text' ? last.text : '';

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages.length, lastText]);

  const send = () => {
    if (!input.trim()) return;
    handleUserText(input);
    setInput('');
  };
  const p = PERSONAS[persona];
  const lastAssistantIdx = messages.map((m) => m.kind === 'text' && m.role === 'assistant').lastIndexOf(true);

  return (
    <div className="mx-auto flex h-full max-w-md flex-col bg-paper">
      <header className="sticky top-0 z-10 border-b border-line bg-paper/95 px-4 pb-2.5 pt-[max(0.75rem,env(safe-area-inset-top))] backdrop-blur">
        <div className="mb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-2xl">{p.avatar}</span>
            <div>
              <div className="font-semibold leading-tight">{p.name}</div>
              <div className="text-[11px] text-muted">品质记账 · {p.tagline}</div>
            </div>
          </div>
          <button onClick={() => setSheet('menu')} className="rounded-full bg-white px-3 py-1.5 text-sm shadow-sm">菜单</button>
        </div>
        {step === 'done' && <button className="block w-full" onClick={() => setSheet('budget')}><WeekSummary /></button>}
      </header>

      <main className="flex-1 space-y-3 overflow-y-auto px-3 py-4">
        {messages.map((m, i) => {
          if (m.kind === 'text') return <TextBubble key={m.id} m={m} isLast={i === lastAssistantIdx && i >= messages.length - 2} openSettings={() => setSheet('settings')} />;
          if (m.kind === 'spend') return <SpendCard key={m.id} spendId={m.spendId} />;
          if (m.kind === 'guess') return <GuessCard key={m.id} msg={m} />;
          if (m.kind === 'review') return <ReviewCard key={m.id} msg={m} />;
          if (m.kind === 'screenshot') return <ScreenshotCard key={m.id} msg={m} />;
          return null;
        })}
        <div ref={endRef} />
      </main>

      <footer className="border-t border-line bg-paper px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2">
        {step === 'done' && (
          <div className="mb-2 flex gap-2 overflow-x-auto text-xs">
            {[['📸 截图记账', pickScreenshot], ['🔔 模拟通知', injectDemoNotification], ['📅 本周复盘', () => runWeekReview(weekKey(Date.now()))], ['🎯 预算', () => setSheet('budget')]].map(([l, fn]) => (
              <button key={l as string} onClick={fn as () => void} className="shrink-0 rounded-full bg-white px-3 py-1 shadow-sm">{l as string}</button>
            ))}
          </div>
        )}
        <div className="flex items-end gap-2">
          <textarea
            rows={1} value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }}
            placeholder={step === 'goal' ? '说说你的目标…' : '记一笔「午饭 35」，或者随便聊聊'}
            className="max-h-28 flex-1 resize-none rounded-2xl border border-line bg-white px-3.5 py-2.5 text-[15px] outline-none focus:border-ink/30"
          />
          <button onClick={send} className="rounded-full bg-ink px-4 py-2.5 text-sm text-white">发送</button>
        </div>
      </footer>

      <input
        ref={(el) => { fileInput = el; }} type="file" accept="image/*" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) addScreenshotFile(f); e.target.value = ''; }}
      />
      {sheet === 'menu' && <Menu onClose={() => setSheet(null)} openSettings={() => setSheet('settings')} openBudget={() => setSheet('budget')} />}
      {sheet === 'settings' && <SettingsSheet onClose={() => setSheet(null)} />}
      {sheet === 'budget' && <BudgetSheet onClose={() => setSheet(null)} />}
    </div>
  );
}
