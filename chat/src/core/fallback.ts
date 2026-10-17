// 没填 API Key 时的本地点评：按人设 × 预算进度挑模板。
import type { PersonaId } from './store';
import { yuan } from './budget';

const pick = <T,>(arr: T[]) => arr[Math.floor(Math.random() * arr.length)];

export function localSpendComment(persona: PersonaId, category: string, amount: number, spent: number, budget: number): string {
  const ratio = budget > 0 ? spent / budget : 0;
  const left = yuan(Math.max(0, budget - spent));
  if (persona === 'warm') {
    if (budget === 0) return `记下啦～${category}这类还没设预算，周日复盘时我们一起定一个吧 🌱`;
    if (ratio <= 0.6) return pick([`${category} ${yuan(amount)} 元，这周${category}还剩 ${left} 元，节奏很稳，继续保持 🌷`, `记好啦！${category}才用到预算的 ${Math.round(ratio * 100)}%，你比自己想象的更会安排～`]);
    if (ratio <= 1) return `这周${category}已经用了 ${Math.round(ratio * 100)}%，还剩 ${left} 元。接下来几天稍微留意一下就好，你可以的 💪`;
    return `${category}这周超出预算 ${yuan(spent - budget)} 元啦。没关系，知道钱去哪了就是进步，周日复盘我们看看要不要调整预算～`;
  }
  if (budget === 0) return `${category}连预算都没有你就敢花？行，先记着，复盘的时候再跟你算账。`;
  if (ratio <= 0.6) return pick([`${category}才花到 ${Math.round(ratio * 100)}%？不错嘛，看来钱包今天不用急救了。`, `${yuan(amount)} 元而已，勉强放你一马。这周${category}还剩 ${left}，别得意。`]);
  if (ratio <= 1) return `${category}已经烧掉 ${Math.round(ratio * 100)}% 了，剩下的 ${left} 元你可得省着花，别让我周日骂你。`;
  return `${category}超支 ${yuan(spent - budget)} 元，恭喜你，成功把预算当成了建议。剩下几天，这一类请自觉闭关。`;
}

export function localReview(persona: PersonaId, over: string[], under: string[]): string {
  if (persona === 'warm') {
    return `这周辛苦啦！${under.length ? `${under.join('、')}都控制在预算内，真棒 🌷` : ''}${over.length ? `${over.join('、')}超了一点，下周预算我已经按实际帮你调好，点「采纳建议预算」就行～` : '整体很健康，继续保持！'}`;
  }
  return `复盘时间到。${over.length ? `${over.join('、')}超支，你的预算在这几类面前就是一张废纸。` : '居然没超支？我都怀疑你偷偷没记账。'}${under.length ? `${under.join('、')}倒是还行，算你有点出息。` : ''}下周建议预算在下面，敢不敢采纳？`;
}
