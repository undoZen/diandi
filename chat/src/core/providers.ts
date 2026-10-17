// 内置 AI 供应商（都用国内版接口，OpenAI 兼容）+ 自定义。
import type { Settings } from './store';

export type ProviderId = 'deepseek' | 'zhipu' | 'kimi' | 'custom';

export interface Provider {
  id: ProviderId;
  name: string;
  baseUrl: string;
  defaultModel: string;
  models: string[];
  /** 申请 API Key 的页面 */
  keyUrl: string;
  keyHint: string;
  /** 接口是否放行浏览器跨域直连；不放行的只能在 App 里走原生请求 */
  browserDirect: boolean;
}

export const PROVIDERS: Provider[] = [
  {
    id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com',
    defaultModel: 'deepseek-flash', models: ['deepseek-flash', 'deepseek-v4-pro'],
    keyUrl: 'https://platform.deepseek.com/api_keys', keyHint: 'DeepSeek 开放平台 → API keys，新建一个',
    browserDirect: true,
  },
  {
    id: 'zhipu', name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    defaultModel: 'glm-4.5-flash', models: ['glm-4.5-flash', 'glm-4.5-air', 'glm-4.6'],
    keyUrl: 'https://open.bigmodel.cn/usercenter/apikeys', keyHint: '智谱开放平台（bigmodel.cn）→ API Keys',
    browserDirect: false,
  },
  {
    id: 'kimi', name: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1',
    defaultModel: 'kimi-k2-turbo-preview', models: ['kimi-k2-turbo-preview', 'kimi-k2-0905-preview', 'moonshot-v1-8k'],
    keyUrl: 'https://platform.moonshot.cn/console/api-keys', keyHint: 'Moonshot 开放平台 → API Key 管理',
    browserDirect: true,
  },
  {
    id: 'custom', name: '自定义', baseUrl: '',
    defaultModel: '', models: [],
    keyUrl: '', keyHint: '任何 OpenAI 兼容接口：填到 /v1 这一级（请求会发到「地址/chat/completions」）',
    browserDirect: true,
  },
];

export const providerOf = (id: ProviderId) => PROVIDERS.find((p) => p.id === id) ?? PROVIDERS[0];

export interface ActiveConfig {
  provider: Provider;
  apiKey: string;
  baseUrl: string;
  model: string;
}

export function activeConfig(s: Settings): ActiveConfig {
  const provider = providerOf(s.provider);
  return {
    provider,
    apiKey: (s.keys[s.provider] ?? '').trim(),
    baseUrl: (provider.id === 'custom' ? s.customBaseUrl : provider.baseUrl).trim().replace(/\/$/, ''),
    model: (s.models[s.provider] || provider.defaultModel).trim(),
  };
}

export const isConfigured = (s: Settings) => {
  const c = activeConfig(s);
  return !!(c.apiKey && c.baseUrl && c.model);
};
