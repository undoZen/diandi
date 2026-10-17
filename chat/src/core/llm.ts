// OpenAI 兼容的 chat/completions。key 只存在本机，请求从设备直接发到供应商：
// - 放行跨域的（DeepSeek、Kimi）：浏览器 fetch 流式直连
// - 不放行的（智谱）或直连被拦：iOS 原生壳走 CapacitorHttp，Android 外壳走本机 Ktor 转发（都在手机上，不经第三方服务器）
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import type { ChatTurn } from '../prompts/tasks';
import { isAndroidShell } from './android';
import type { ActiveConfig } from './providers';

function errorText(status: number, body: string): string {
  let msg = body.slice(0, 120);
  try { msg = JSON.parse(body).error?.message ?? msg; } catch { /* 非 JSON 原样展示 */ }
  if (status === 401) msg = 'API Key 无效';
  if (status === 402) msg = '账户余额不足';
  if (status === 429) msg = '请求太频繁或额度用完';
  return `${status} ${msg}`;
}

async function streamDirect(cfg: ActiveConfig, messages: ChatTurn[], onDelta: (t: string) => void): Promise<string> {
  const res = await fetch(`${cfg.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cfg.apiKey}` },
    body: JSON.stringify({ model: cfg.model, messages, stream: true }),
  });
  if (!res.ok || !res.body) throw new Error(errorText(res.status, await res.text().catch(() => '')));
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

/** 非流式：原生 HTTP / 本机转发，绕开浏览器跨域限制 */
async function completeNative(cfg: ActiveConfig, messages: ChatTurn[]): Promise<string> {
  const url = `${cfg.baseUrl}/chat/completions`;
  const body = { model: cfg.model, messages, stream: false };
  let status: number;
  let text: string;
  if (Capacitor.isNativePlatform()) {
    const res = await CapacitorHttp.post({
      url, data: body, responseType: 'text',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cfg.apiKey}` },
    });
    status = res.status;
    text = typeof res.data === 'string' ? res.data : JSON.stringify(res.data);
  } else if (isAndroidShell()) {
    const res = await fetch('/api/llm-proxy', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, apiKey: cfg.apiKey, body: JSON.stringify(body) }),
    });
    status = res.status;
    text = await res.text();
  } else {
    throw new Error(`${cfg.provider.name} 不支持网页直连，请在 App 里使用，或换 DeepSeek / Kimi`);
  }
  if (status < 200 || status >= 300) throw new Error(errorText(status, text));
  return JSON.parse(text).choices?.[0]?.message?.content ?? '';
}

export async function streamChat(cfg: ActiveConfig, messages: ChatTurn[], onDelta: (text: string) => void): Promise<string> {
  if (!cfg.provider.browserDirect) {
    const text = await completeNative(cfg, messages);
    onDelta(text);
    return text;
  }
  try {
    return await streamDirect(cfg, messages, onDelta);
  } catch (e) {
    // 自定义接口可能不放行跨域：fetch 抛 TypeError，在 App 里退到原生请求
    if (e instanceof TypeError && (Capacitor.isNativePlatform() || isAndroidShell())) {
      const text = await completeNative(cfg, messages);
      onDelta(text);
      return text;
    }
    if (e instanceof TypeError) throw new Error('连不上接口（网络问题，或该接口不允许网页直连）');
    throw e;
  }
}

/** 设置页「测试连接」 */
export async function testConnection(cfg: ActiveConfig): Promise<string> {
  return streamChat(cfg, [{ role: 'user', content: '用一句话打个招呼，10 个字以内。' }], () => {});
}
