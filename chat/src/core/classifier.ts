// 移植自 app/.../classify/{Tokenizer,Classifier}.kt：精确商户 → 关键词 → 朴素贝叶斯 bigram。
// model.json 是 diandi 导出的纯聚合统计（无原始流水），可安全随前端发布。
import rawModel from './model.json';
import { extractChannel } from './parser';

export const CATEGORIES: string[] = rawModel.categories;
export type Category = string;

export interface Prediction {
  category: Category;
  confidence: number;
  uncertain: boolean;
  candidates: [Category, number][];
}

const CORRECTION_WEIGHT = 3;
const ALPHA = 0.5;
const TOP_TOKENS = 15;
const MIN_TOKEN_COUNT = 2;
const EXACT_SHARE = 0.8;
const UNCERTAIN_TOP = 0.5;
const UNCERTAIN_MARGIN = 0.15;

/**
 * 对话手记的关键词表（diandi 的 model.json 关键词为空，银行商户名靠贝叶斯；
 * 手记里是「午饭」「打车」这类口语，靠这张表兜住）。按长度降序匹配。
 */
const KEYWORDS: Record<string, string[]> = {
  餐饮: ['早饭', '早餐', '午饭', '午餐', '晚饭', '晚餐', '夜宵', '宵夜', '外卖', '火锅', '烧烤', '食堂', '餐厅', '饭店', '酒家', '菜馆', '小吃', '老乡鸡', '米线', '麻辣烫', '饺', '包子', '粉', '汉堡', '披萨', '寿司', '料理', '鸡', '饭', '面', '麦当劳', '肯德基', '海底捞', '餐'],
  零食饮料: ['咖啡', '奶茶', '星巴克', '瑞幸', '喜茶', '蜜雪', '霸王茶姬', '饮料', '零食', '水果', '蛋糕', '甜品'],
  交通: ['打车', '滴滴', '地铁', '公交', '高铁', '火车', '机票', '加油', '停车', '出租', '单车', '过路费', '油费'],
  网购: ['淘宝', '天猫', '京东', '拼多多', '网购', '快递', '买衣服', '衣服', '鞋'],
  便利店: ['便利店', '全家', '罗森', '7-11', '711', '超市', '买菜', '盒马'],
  订阅缴费: ['话费', '电费', '水费', '燃气', '房租', '物业', '会员', '订阅', '宽带', 'VIP', '网费'],
  医药: ['药', '医院', '挂号', '体检', '看病', '牙', '诊所'],
  娱乐: ['电影', '游戏', 'KTV', '演唱会', '门票', '健身', '旅游', '酒吧', '剧本杀', '书'],
};

export function normalize(s: string): string {
  let out = '';
  for (const ch of s.trim()) {
    let c = ch;
    const code = ch.codePointAt(0)!;
    if (ch === '（') c = '(';
    else if (ch === '）') c = ')';
    else if (ch === '　') c = ' ';
    else if (code >= 0xff01 && code <= 0xff5e) c = String.fromCharCode(code - 0xfee0);
    out += c.toUpperCase();
  }
  out = out.trim();
  const lp = out.lastIndexOf('(');
  if (lp >= 0 && out.indexOf(')', lp) < 0) out = out.slice(0, lp).trim();
  return out;
}

export function tokenize(merchant: string): string[] {
  const normalized = normalize(merchant);
  if (!normalized) return [];
  const tokens = [`M:${normalized}`];
  const [channel, body] = extractChannel(normalized);
  if (channel) tokens.push(`C:${channel}`);
  let run = '';
  let runIsCjk = false;
  const flush = () => {
    if (!run) return;
    if (runIsCjk) {
      if (run.length === 1) tokens.push(run);
      else for (let i = 0; i < run.length - 1; i++) tokens.push(run.slice(i, i + 2));
    } else tokens.push(run);
    run = '';
  };
  for (const ch of body) {
    const code = ch.codePointAt(0)!;
    const cjk = code >= 0x4e00 && code <= 0x9fff;
    const word = cjk || /[\p{L}\p{N}*]/u.test(ch);
    if (!word) { flush(); continue; }
    if (run && cjk !== runIsCjk) flush();
    runIsCjk = cjk;
    run += ch;
  }
  flush();
  return tokens;
}

interface Model {
  docCount: number[];
  tokenTotal: number[];
  tokenCount: Map<string, number[]>;
  merchantIndex: Map<string, number[]>;
  vocabSize: number;
  keywords: [string, number][];
}

let model: Model | null = null;

/** 用基础模型 + 用户修正（权重 3）重建；全量 <50ms，每次修正后直接调 */
export function buildModel(corrections: { merchant: string; category: Category }[] = []): void {
  const k = CATEGORIES.length;
  const docCount = [...rawModel.docCount];
  const tokenTotal = [...rawModel.tokenTotal];
  const tokenCount = new Map<string, number[]>(
    Object.entries(rawModel.tokenCount as Record<string, number[]>).map(([t, v]) => [t, [...v]]),
  );
  const merchantIndex = new Map<string, number[]>(
    Object.entries(rawModel.merchantIndex as Record<string, number[]>).map(([t, v]) => [t, [...v]]),
  );
  for (const { merchant, category } of corrections) {
    const idx = CATEGORIES.indexOf(category);
    if (idx < 0 || !merchant) continue;
    docCount[idx] += CORRECTION_WEIGHT;
    const norm = normalize(merchant);
    if (!merchantIndex.has(norm)) merchantIndex.set(norm, new Array(k).fill(0));
    merchantIndex.get(norm)![idx] += CORRECTION_WEIGHT;
    for (const t of tokenize(merchant)) {
      if (!tokenCount.has(t)) tokenCount.set(t, new Array(k).fill(0));
      tokenCount.get(t)![idx] += CORRECTION_WEIGHT;
      tokenTotal[idx] += CORRECTION_WEIGHT;
    }
  }
  const keywords: [string, number][] = [];
  for (const [cat, words] of Object.entries(KEYWORDS)) {
    const idx = CATEGORIES.indexOf(cat);
    if (idx >= 0) for (const w of words) keywords.push([normalize(w), idx]);
  }
  keywords.sort((a, b) => b[0].length - a[0].length);
  model = { docCount, tokenTotal, tokenCount, merchantIndex, vocabSize: tokenCount.size, keywords };
}

export function classify(merchant: string | null): Prediction {
  if (!model) buildModel();
  const m = model!;
  const other = '其他';
  if (!merchant?.trim()) return { category: other, confidence: 0, uncertain: true, candidates: [] };
  const normalized = normalize(merchant);

  const exact = m.merchantIndex.get(normalized);
  if (exact) {
    const total = exact.reduce((a, b) => a + b, 0);
    if (total > 0) {
      const best = exact.indexOf(Math.max(...exact));
      const share = exact[best] / total;
      if (share >= EXACT_SHARE) {
        return { category: CATEGORIES[best], confidence: share, uncertain: false, candidates: [[CATEGORIES[best], share]] };
      }
    }
  }
  for (const [word, idx] of m.keywords) {
    if (word && normalized.includes(word)) {
      return { category: CATEGORIES[idx], confidence: 0.95, uncertain: false, candidates: [[CATEGORIES[idx], 0.95]] };
    }
  }
  return bayes(m, merchant);
}

function bayes(m: Model, merchant: string): Prediction {
  const k = CATEGORIES.length;
  const n = m.docCount.reduce((a, b) => a + b, 0);
  const scored = [...new Set(tokenize(merchant))].flatMap((t) => {
    const counts = m.tokenCount.get(t);
    if (!counts || counts.reduce((a, b) => a + b, 0) < MIN_TOKEN_COUNT) return [];
    const probs = counts.map((c, i) => (c + ALPHA) / (m.tokenTotal[i] + ALPHA * m.vocabSize));
    const norm = probs.reduce((a, b) => a + b, 0);
    const shares = probs.map((p) => p / norm);
    return [{ t, interest: Math.max(...shares) - Math.min(...shares) }];
  }).sort((a, b) => b.interest - a.interest);
  const selected = [...new Set([
    ...scored.filter((s) => s.t.startsWith('M:')).map((s) => s.t),
    ...scored.filter((s) => !s.t.startsWith('M:')).slice(0, TOP_TOKENS).map((s) => s.t),
  ])];
  if (selected.length === 0) {
    return { category: '其他', confidence: 0, uncertain: true, candidates: [] };
  }
  const logScore = m.docCount.map((d) => Math.log((d + 1) / (n + k)));
  for (const t of selected) {
    const counts = m.tokenCount.get(t)!;
    for (let c = 0; c < k; c++) logScore[c] += Math.log((counts[c] + ALPHA) / (m.tokenTotal[c] + ALPHA * m.vocabSize));
  }
  const maxLog = Math.max(...logScore);
  const exps = logScore.map((s) => Math.exp(s - maxLog));
  const sum = exps.reduce((a, b) => a + b, 0);
  const post = exps.map((e) => e / sum);
  const order = post.map((_, i) => i).sort((a, b) => post[b] - post[a]);
  const topP = post[order[0]];
  const secondP = k > 1 ? post[order[1]] : 0;
  return {
    category: CATEGORIES[order[0]],
    confidence: topP,
    uncertain: topP < UNCERTAIN_TOP || topP - secondP < UNCERTAIN_MARGIN,
    candidates: order.slice(0, 3).map((i) => [CATEGORIES[i], post[i]]),
  };
}
