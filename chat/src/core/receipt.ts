// 支付截图 OCR 文本 → 金额 + 商户 + 时间。覆盖微信/支付宝的「支付成功」页与「账单详情」页、外卖订单页。
// 输入是按行的识别结果（iOS Vision 或浏览器 tesseract），全部在本机处理。
import { toCents } from './parser';

export interface ReceiptParse {
  amountCents: number;
  merchant: string | null;
  txTime: number | null;
  rawText: string;
}

const REJECT = /退款成功|已退款|收款成功|已收款|已存入零钱|转账收款|收入金额/;
const NOISE_LINE = /优惠|立减|原价|积分|余额宝收益|红包抵扣|满\d+减|运费|配送费说明|折扣/;
const HEADER = /^(支付成功|交易成功|付款成功|账单详情|当前状态|订单详情|完成|已完成|订单已完成|订单已送达|返回|全部账单|支付结果)$/;

/** 全角→半角、去掉数字间的空格（OCR 常把 35.00 识别成 35. 00） */
export function normalizeLine(s: string): string {
  let out = '';
  for (const ch of s) {
    const code = ch.codePointAt(0)!;
    if (code >= 0xff01 && code <= 0xff5e && ch !== '￥') out += String.fromCharCode(code - 0xfee0);
    else if (ch === '　') out += ' ';
    else out += ch;
  }
  return out
    .replace(/(\d)\s*([.,])\s*(\d)/g, '$1$2$3')
    .replace(/([¥￥-])\s+(\d)/g, '$1$2')
    .replace(/[|｜]/g, ' ')
    // tesseract 识别中文时常在字与字之间插空格
    .replace(/([\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])/g, '$1')
    .trim();
}

const LABELS: [RegExp, number][] = [
  [/^(收款方|收款商户|收款人)/, 6],
  [/^(商户全称|商户名称|商家名称|商户)/, 5],
  [/^(商家|店铺|门店)/, 4],
  [/^(商品说明|商品名称|商品)(?=[\s:：]|$)/, 3],
];

function valueAfterLabel(lines: string[], i: number, label: RegExp): string | null {
  const rest = lines[i].replace(label, '').replace(/^[\s:：]+/, '').trim();
  if (rest) return rest;
  const next = lines[i + 1]?.trim();
  return next && !LABELS.some(([r]) => r.test(next)) ? next : null;
}

function parseTime(text: string): number | null {
  let m = text.match(/(\d{4})[年\-/.](\d{1,2})[月\-/.](\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?/);
  if (m) return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] ?? 0)).getTime();
  m = text.match(/(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})/);
  if (m) {
    const now = new Date();
    return new Date(now.getFullYear(), +m[1] - 1, +m[2], +m[3], +m[4]).getTime();
  }
  return null;
}

export function parseReceipt(rawLines: string[]): ReceiptParse | null {
  const lines = rawLines.map(normalizeLine).filter(Boolean);
  const text = lines.join('\n');
  if (REJECT.test(text)) return null;

  // ── 金额：打分取最高 ──
  interface Hit { cents: number; score: number; line: number }
  const hits: Hit[] = [];
  lines.forEach((line, i) => {
    if (NOISE_LINE.test(line)) return;
    const labeled = line.match(/(实付款?|实付金额|支付金额|付款金额|订单金额|合计|消费金额|交易金额)[:：]?\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)/);
    if (labeled) {
      const c = toCents(labeled[2]);
      if (c) hits.push({ cents: c, score: /实付|支付金额|付款金额/.test(labeled[1]) ? 10 : 7, line: i });
      return;
    }
    // 单独成行的金额（成功页/账单页的大字）
    const alone = line.match(/^[-−]?\s*[¥￥]?\s*(\d[\d,]*\.\d{2})(元)?$/);
    if (alone) {
      const c = toCents(alone[1]);
      if (c) hits.push({ cents: c, score: line.startsWith('-') || line.startsWith('−') ? 9 : 8, line: i });
      return;
    }
    for (const m of line.matchAll(/[¥￥]\s*(\d[\d,]*(?:\.\d{1,2})?)/g)) {
      const c = toCents(m[1]);
      if (c) hits.push({ cents: c, score: 4, line: i });
    }
  });
  if (hits.length === 0) return null;
  hits.sort((a, b) => b.score - a.score || a.line - b.line);
  const best = hits[0];

  // ── 商户：标签优先，否则取大字金额上方最近的非标题行 ──
  let merchant: string | null = null;
  let merchantScore = 0;
  lines.forEach((line, i) => {
    for (const [label, score] of LABELS) {
      if (label.test(line) && score > merchantScore) {
        const v = valueAfterLabel(lines, i, label);
        if (v && !/^\d/.test(v) && !/[¥￥]/.test(v)) { merchant = v; merchantScore = score; }
      }
    }
  });
  // 账单详情页大字金额上方的标题就是商户/门店名（比「商户全称」好认，比「收款方」弱）
  if (merchantScore < 6) {
    for (let i = best.line - 1; i >= 0 && i >= best.line - 3; i--) {
      const l = lines[i];
      if (!HEADER.test(l) && !/^\d/.test(l) && !/[:：¥￥]/.test(l) && !LABELS.some(([r]) => r.test(l)) && l.length >= 2 && l.length <= 30) {
        if (!merchant || best.score >= 8) merchant = l;
        break;
      }
    }
  }
  // 订单页（金额在底部「实付」）：取页面顶部第一个像店名的行
  if (!merchant) {
    merchant = lines.slice(0, 4).find((l) =>
      !HEADER.test(l) && !/^\d/.test(l) && !/[:：¥￥]/.test(l) && l.length >= 2 && l.length <= 30) ?? null;
  }
  const m = merchant as string | null;
  return {
    amountCents: best.cents,
    merchant: m ? m.replace(/[>›》]+$/, '').replace(/\s*([()])\s*/g, '$1').trim().slice(0, 40) : null,
    txTime: parseTime(text),
    rawText: text,
  };
}
