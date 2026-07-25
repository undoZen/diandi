import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';

/** 是否在 App 内 WebView（UA 带 DiandiApp 标记，MainActivity 注入） */
const isInApp = navigator.userAgent.includes('DiandiApp');

export default function Home() {
  const [ping, setPing] = useState('…');
  useEffect(() => {
    const load = () =>
      api
        .ping()
        .then((p) => {
          const s = Math.round(p.uptimeMs / 1000);
          const text =
            s >= 3600
              ? `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
              : s >= 60
                ? `${Math.floor(s / 60)}m ${s % 60}s`
                : `${s}s`;
          setPing(`服务在线 · uptime ${text}`);
        })
        .catch((e) => setPing('服务未就绪: ' + e.message));
    load();
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="space-y-4 p-4">
      <div>
        <h1 className="text-xl font-semibold">点滴集</h1>
        <p className="mt-1 text-sm text-ink-muted">{ping}</p>
      </div>

      <div className="grid gap-3">
        <Link
          to="/spending"
          className="block rounded-xl bg-ink-card p-4 active:bg-ink-line"
        >
          <div className="font-medium">消费记录</div>
          <div className="text-sm text-ink-muted">分类、修正、手动录入</div>
        </Link>
        <Link
          to="/dashboard"
          className="block rounded-xl bg-ink-card p-4 active:bg-ink-line"
        >
          <div className="font-medium">统计</div>
          <div className="text-sm text-ink-muted">7 / 30 / 90 天消费趋势</div>
        </Link>
        {/* /native/* 由 App 内 WebView 拦截拉起原生屏，仅 App 内（UA 带 DiandiApp 标记）展示 */}
        {isInApp && (
          <>
            <a
              href="/native/notifications"
              className="block rounded-xl bg-ink-card p-4 active:bg-ink-line"
            >
              <div className="font-medium">通知列表</div>
              <div className="text-sm text-ink-muted">全部已采集通知</div>
            </a>
            <a
              href="/native/setup"
              className="block rounded-xl bg-ink-card p-4 active:bg-ink-line"
            >
              <div className="font-medium">设置与引导</div>
              <div className="text-sm text-ink-muted">权限、保活、iPhone 采集（高级功能）</div>
            </a>
          </>
        )}
      </div>
    </div>
  );
}
