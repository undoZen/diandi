// Web 与 RN 共用的小工具。纯函数、无平台依赖。

export function yuan(cents: number): string {
  return (cents / 100).toFixed(2);
}

function pad(n: number): string {
  return n < 10 ? '0' + n : String(n);
}

/** 通知列表时间：今天显示 HH:MM，否则 M-D HH:MM */
export function fmtNotifTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return d.toDateString() === now.toDateString() ? hm : `${d.getMonth() + 1}-${d.getDate()} ${hm}`;
}

/** HH:MM */
export function hm(ts: number): string {
  const d = new Date(ts);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** M月D日 */
export function dayLabel(ts: number): string {
  const d = new Date(ts);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

/** 非零填充日期键 YYYY-M-D（spending 按日分组用，与原 Pages.kt 一致） */
export function dayKey(ts: number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

/** 零填充日期键 YYYY-MM-DD（dashboard 趋势轴用） */
export function dayKeyPad(ts: number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
