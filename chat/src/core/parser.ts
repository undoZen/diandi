// 移植自 app/.../spend/SpendParser.kt：银行通知 → 金额（分）+ 商户/渠道。纯函数。

export interface ParsedSpend {
  amountCents: number;
  merchant: string | null;
  channel: string | null;
  rawText: string;
}

/** 非消费类通知，整条拒绝 */
const REJECT = /还款|入账|转入|退款|退货|发放|利息|账单|逾期|分期|转账/;
const AMOUNT = String.raw`([\d,]+(?:\.\d{1,2})?)`;

const CMB_CARD = new RegExp(String.raw`您尾号\d{4}的招行信用卡消费${AMOUNT}(?:元|人民币)`);
const CMB_QUICK = new RegExp(String.raw`在【(.+?)】发生(?:快捷支付)?扣款[，,]\s*人民币${AMOUNT}`);
const CMB_LIFE = new RegExp(String.raw`您在(.+?)有一笔${AMOUNT}人民币的消费已成功`);
const ICBC = new RegExp(String.raw`支出[(（]消费(.+?)[)）]${AMOUNT}元`);

const CHANNELS = new Set([
  '财付通', '支付宝', '京东支付', '美团支付', '云闪付', '翼支付', '抖音支付', '度小满', '苏宁易付宝',
]);

function normalizePunct(s: string): string {
  return s.replace(/（/g, '(').replace(/）/g, ')').replace(/：/g, ':').replace(/，/g, ',');
}

/** "1,234.5" → 123450 分。整数运算，绝不过浮点 */
export function toCents(s: string): number | null {
  const parts = s.replace(/,/g, '').split('.');
  if (parts.length > 2 || !/^\d+$/.test(parts[0])) return null;
  const fracStr = (parts[1] ?? '').padEnd(2, '0').slice(0, 2);
  if (!/^\d{2}$/.test(fracStr)) return null;
  const cents = Number(parts[0]) * 100 + Number(fracStr);
  return cents >= 1 && cents <= 100_000_000 ? cents : null;
}

/** "财付通-微信支付-M Stand" → ["财付通-微信支付", "M Stand"] */
export function extractChannel(merchant: string): [string | null, string] {
  const i = merchant.indexOf('-');
  if (i <= 0) return [null, merchant];
  const prefix = merchant.slice(0, i);
  if (!CHANNELS.has(prefix)) return [null, merchant];
  let channel = prefix;
  let body = merchant.slice(i + 1);
  if (body.startsWith('微信支付-')) {
    channel = `${channel}-微信支付`;
    body = body.slice('微信支付-'.length);
  }
  return [channel, body];
}

/** 银行/支付通知文案解析；失配返回 null */
export function parseBankNotification(raw: string, title = ''): ParsedSpend | null {
  if (REJECT.test(`${title} ${raw}`)) return null;
  const text = normalizePunct(raw);
  let merchant: string | null = null;
  let amount: string | null = null;
  let m: RegExpMatchArray | null;
  if ((m = text.match(CMB_QUICK))) [merchant, amount] = [m[1], m[2]];
  else if ((m = text.match(CMB_CARD))) amount = m[1];
  else if ((m = text.match(CMB_LIFE))) [merchant, amount] = [m[1], m[2]];
  else if ((m = text.match(ICBC))) [merchant, amount] = [m[1], m[2]];
  else return null;
  const cents = toCents(amount!);
  if (cents == null) return null;
  const mer = merchant?.trim() || null;
  return { amountCents: cents, merchant: mer, channel: mer ? extractChannel(mer)[0] : null, rawText: raw };
}

/**
 * 对话手记：「午饭 35」「打车28元」「星巴克 ¥32.5」「买菜花了 86.4」。
 * 只认短句（≤40 字）、恰好一个金额、不是问句；返回金额与描述（描述当商户喂分类器）。
 */
export function parseChatEntry(input: string): ParsedSpend | null {
  const text = input.trim();
  if (!text || text.length > 40) return null;
  if (/[?？吗呢]$/.test(text) || /多少|怎么|为什么|如何/.test(text)) return null;
  if (/收入|工资|退款|还款|转账|红包收/.test(text)) return null;
  const nums = [...text.matchAll(/[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*(元|块|rmb|RMB)?/g)];
  if (nums.length !== 1) return null;
  const n = nums[0];
  const cents = toCents(n[1]);
  if (cents == null) return null;
  const desc = (text.slice(0, n.index) + ' ' + text.slice(n.index! + n[0].length))
    .replace(/花了|花|消费|付了|买了|共|一共|大概|约/g, ' ')
    .replace(/[,，。.!！\s]+/g, ' ')
    .trim();
  if (!desc) return null; // 光一个数字不算记账
  return { amountCents: cents, merchant: desc, channel: null, rawText: text };
}
