// 提示词系统：system = 人设 + 用户画像（仅目标与预算）+ 任务说明 + 输出约束。
// 所有喂给模型的数据都只到「类别」粒度；商户、卡号、原文、精确时间一律不出现在这里。
import { CATEGORIES } from '../core/classifier';
import { liveSpends, sumByCategory, total, weekKey, weekRange, yuan } from '../core/budget';
import type { ReviewRow, State } from '../core/store';
import { PERSONAS } from './personas';

export interface ChatTurn { role: 'system' | 'user' | 'assistant'; content: string }

const COMMON_RULES = [
  '通用规则：',
  '- 用简体中文；金额单位是人民币元。',
  '- 你只能看到消费类别和金额，看不到商户和明细，不要编造具体买了什么。',
  '- 不推荐任何具体理财产品、股票、贷款。',
].join('\n');

/** 本周各类 已花/预算 的一张小表（只含类别级数据） */
export function weekContext(s: State, now = Date.now()): string {
  const spent = sumByCategory(liveSpends(s), weekRange(weekKey(now)));
  const lines = CATEGORIES
    .filter((c) => (s.profile.budgets[c] ?? 0) > 0 || spent[c] > 0)
    .map((c) => `${c} ${yuan(spent[c])}/${yuan(s.profile.budgets[c] ?? 0)}`);
  const day = ((new Date(now).getDay() + 6) % 7) + 1;
  return [
    `今天是本周第 ${day} 天（周一为第 1 天）。`,
    `本周合计 ${yuan(total(spent))}/${yuan(total(s.profile.budgets))} 元。`,
    `各类 已花/周预算：${lines.join('；') || '暂无'}`,
  ].join('\n');
}

function system(s: State, task: string): string {
  const p = PERSONAS[s.profile.persona];
  return [
    p.voice,
    COMMON_RULES,
    `用户的目标：${s.profile.goal || '先搞清楚钱都花到哪里了'}`,
    task,
  ].join('\n\n');
}

export function spendCommentPrompt(s: State, category: string, amountCents: number): ChatTurn[] {
  const p = PERSONAS[s.profile.persona];
  const user = [
    `刚刚一笔消费：${category} ${yuan(amountCents)} 元。`,
    weekContext(s),
  ].join('\n');
  return [
    { role: 'system', content: system(s, [
      '任务：对用户刚发生的这一笔消费做一句点评。',
      '- 40~80 字，一段话，不要列表，不要标题。',
      '- 结合这一类本周的预算进度说话。',
      '参考风格示例：',
      ...p.examples.map((e) => `「${e}」`),
    ].join('\n')) },
    { role: 'user', content: user },
  ];
}

export function reviewPrompt(s: State, period: 'week' | 'month', label: string, rows: ReviewRow[]): ChatTurn[] {
  const table = rows.map((r) =>
    `${r.category}：实际 ${yuan(r.actualCents)}，预算 ${yuan(r.budgetCents)}` +
    (period === 'week' ? `，下周建议 ${yuan(r.suggestCents)}` : '') +
    (s.profile.guesses[r.category] != null ? `，当初自己猜 ${yuan(s.profile.guesses[r.category])}/周` : ''),
  ).join('\n');
  const user = [`${label}复盘数据（单位元）：`, table,
    `合计：实际 ${yuan(rows.reduce((a, r) => a + r.actualCents, 0))}，预算 ${yuan(rows.reduce((a, r) => a + r.budgetCents, 0))}`].join('\n');
  return [
    { role: 'system', content: system(s, [
      `任务：陪用户做${label}复盘。`,
      '- 150~250 字。先一句总评，再点出 1~2 个最值得注意的类别（超支最多或进步最大），',
      '  对比「当初猜的」和「实际花的」，帮用户更了解自己。',
      period === 'week'
        ? '- 最后告诉用户：下周预算已按实际情况给出建议，可以点下面的「采纳建议预算」。'
        : '- 最后给出下个月一个最重要的小目标。',
      '- 如果医药类花费明显增加，或者用户的目标里提到家人、健康、车、房，可以在结尾用一句话温和提醒：',
      '  「花钱之外，也可以顺手体检一下自己的保障（医疗/意外）」，并说菜单里「社区」可以找人聊聊。没有这些情况就不要提保险。',
      '- 可以用少量分段，不要用 Markdown 表格。',
    ].join('\n')) },
    { role: 'user', content: user },
  ];
}

export function chatPrompt(s: State, history: ChatTurn[]): ChatTurn[] {
  return [
    { role: 'system', content: system(s, [
      '任务：和用户自由聊天，围绕花钱、预算、省钱习惯、财务成长。',
      '- 回答简短（一般 120 字以内），像聊天而不是写文章。',
      '- 用户想记账时提醒他直接发「午饭 35」这样的短句就能记。',
      '以下是用户本周的类别级预算情况，可参考：',
      weekContext(s),
    ].join('\n')) },
    ...history,
  ];
}

export function onboardingSummaryPrompt(s: State): ChatTurn[] {
  const lines = CATEGORIES.filter((c) => (s.profile.budgets[c] ?? 0) > 0)
    .map((c) => `${c} ${yuan(s.profile.budgets[c])}`).join('；');
  return [
    { role: 'system', content: system(s, [
      '任务：用户刚完成初始设置。用你的人设做一个 60~100 字的自我介绍兼开场：',
      '- 复述用户的目标，评价一下他猜的每周预算（只挑一两类说）。',
      '- 告诉他：消费会自动进来（安卓）或者直接发「午饭 35」记一笔，每周一起复盘。',
    ].join('\n')) },
    { role: 'user', content: `我的每周预算（元）：${lines}；合计 ${yuan(total(s.profile.budgets))}。` },
  ];
}
