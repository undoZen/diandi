// OpenAI 兼容的流式 chat/completions；浏览器直连（DeepSeek 已放行 CORS），key 只存在本机。
import type { ChatTurn } from '../prompts/tasks';
import type { Settings } from './store';

export async function streamChat(
  settings: Settings,
  messages: ChatTurn[],
  onDelta: (text: string) => void,
  signal?: AbortSignal,
): Promise<string> {
  const res = await fetch(`${settings.baseUrl.replace(/\/$/, '')}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${settings.apiKey}` },
    body: JSON.stringify({ model: settings.model, messages, stream: true, temperature: 1.0 }),
    signal,
  });
  if (!res.ok || !res.body) {
    const body = await res.text().catch(() => '');
    let msg = body.slice(0, 120);
    try { msg = JSON.parse(body).error?.message ?? msg; } catch { /* 非 JSON 原样展示 */ }
    if (res.status === 401) msg = 'API Key 无效';
    if (res.status === 402) msg = '账户余额不足';
    throw new Error(`${res.status} ${msg}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let full = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const lines = buf.split('\n');
    buf = lines.pop() ?? '';
    for (const line of lines) {
      const t = line.trim();
      if (!t.startsWith('data:')) continue;
      const data = t.slice(5).trim();
      if (data === '[DONE]') return full;
      try {
        const delta = JSON.parse(data).choices?.[0]?.delta?.content;
        if (delta) { full += delta; onDelta(full); }
      } catch { /* 半包，等下一段 */ }
    }
  }
  return full;
}
