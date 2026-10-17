import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  CATEGORIES, chooseBudget, chooseGoal, choosePersona, dismissReview, finishOnboarding, handleUserText,
  injectDemoNotification, runMonthReview, runWeekReview,
} from './core/agent';
import { isAndroidShell } from './core/android';
import { CATEGORY_EMOJI, monthKey, weekKey, yuan } from './core/budget';
import { patchProfile, resetAll, setState, useStore, type Chip, type Message } from './core/store';
import { PERSONAS } from './prompts/personas';
import { GuessCard, ReviewCard, SpendCard, WeekSummary } from './components/Cards';

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

function runChip(c: Chip, openSettings: () => void) {
  switch (c.action) {
    case 'persona': return choosePersona(c.arg as 'warm' | 'roast');
    case 'goal': return chooseGoal(c.arg!);
    case 'budget': return chooseBudget(Number(c.arg));
    case 'settings': return openSettings();
    case 'finish-onboarding': return finishOnboarding();
    case 'send': return handleUserText(c.arg!);
    case 'demo': return injectDemoNotification();
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
  const save = () => {
    setState((s) => ({ ...s, settings: { ...draft, apiKey: draft.apiKey.trim() } }));
    onClose();
    if (step === 'ai') finishOnboarding();
  };
  const field = (label: string, key: keyof typeof draft, ph: string, type = 'text') => (
    <label className="mb-3 block">
      <div className="mb-1 text-sm text-muted">{label}</div>
      <input type={type} value={draft[key]} placeholder={ph} onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
        className="w-full rounded-xl border border-line bg-white px-3 py-2 text-sm outline-none focus:border-ink/40" />
    </label>
  );
  return (
    <Sheet title="AI 设置" onClose={onClose}>
      {field('API Key（只存本机）', 'apiKey', 'sk-…', 'password')}
      {field('接口地址（OpenAI 兼容）', 'baseUrl', 'https://api.deepseek.com')}
      {field('模型', 'model', 'deepseek-chat')}
      <p className="mb-4 text-xs leading-relaxed text-muted">
        浏览器直接请求上面的接口，不经过任何中转服务器。AI 只会收到消费类别、金额和预算进度，不会收到商户名和通知原文；
        每条回复下的「🔒 本次发给 AI 的内容」可以核对。
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
        {item('🔔', '模拟一条银行消费通知', injectDemoNotification)}
        {item('📅', '本周复盘', () => runWeekReview(weekKey(Date.now())))}
        {item('🗓️', '本月复盘', () => runMonthReview(monthKey(Date.now())))}
        {item('🎯', '调整每周预算', openBudget)}
        {item(PERSONAS[other].avatar, `换成「${PERSONAS[other].name}」· ${PERSONAS[other].tagline}`, () => choosePersona(other))}
        {item('⚙️', 'AI 设置', openSettings)}
        {item('💬', '社区：和大家聊聊记账与保障', () => window.open(COMMUNITY_URL, '_blank'))}
        {item('🧹', '清空本机数据重新开始', () => { if (confirm('确定清空本机所有记账与对话？')) { resetAll(); location.reload(); } })}
      </div>
      <p className="mt-4 text-center text-xs text-muted">{isAndroidShell() ? '已连接本机通知采集' : '数据仅存于本浏览器'} · 点滴集</p>
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
              <div className="text-[11px] text-muted">点滴集 · {p.tagline}</div>
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
          return null;
        })}
        <div ref={endRef} />
      </main>

      <footer className="border-t border-line bg-paper px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2">
        {step === 'done' && (
          <div className="mb-2 flex gap-2 overflow-x-auto text-xs">
            {[['🔔 模拟通知', injectDemoNotification], ['📅 本周复盘', () => runWeekReview(weekKey(Date.now()))], ['🎯 预算', () => setSheet('budget')]].map(([l, fn]) => (
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

      {sheet === 'menu' && <Menu onClose={() => setSheet(null)} openSettings={() => setSheet('settings')} openBudget={() => setSheet('budget')} />}
      {sheet === 'settings' && <SettingsSheet onClose={() => setSheet(null)} />}
      {sheet === 'budget' && <BudgetSheet onClose={() => setSheet(null)} />}
    </div>
  );
}
