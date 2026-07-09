// 对齐 diandi WebServerService 各 /api/* 路由的返回字段

export interface NotificationMessage {
  sender: string | null;
  time: number;
  text: string | null;
}

export interface NotificationItem {
  id: number;
  packageName: string;
  appName: string;
  postTime: number;
  category: string | null;
  title: string | null;
  text: string | null;
  bigText: string | null;
  textLines: string | null;
  messages: NotificationMessage[] | null;
  isOngoing: boolean;
  removedAt: number | null;
}

export interface NotificationParams {
  limit?: number;
  /** 'finance' 只看财务筛选组；缺省=全部 */
  filter?: 'finance';
}

export interface SpendingCandidate {
  category: string;
  prob: number;
}

export interface SpendingItem {
  id: number;
  notificationId: number;
  sourcePackage: string;
  txTime: number;
  amountCents: number;
  merchant: string | null;
  channel: string | null;
  category: string;
  confidence: number;
  uncertain: boolean;
  corrected: boolean;
  hidden: boolean;
  createdAt: number;
  candidates: SpendingCandidate[];
}

export interface SpendingParams {
  limit?: number;
  /** 只取最近 N 天（dashboard 统计用） */
  days?: number;
}

export interface PingResponse {
  status: string;
  time: number;
  uptimeMs: number;
}
