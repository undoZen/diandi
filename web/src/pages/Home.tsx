import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';

export default function Home() {
  const [ping, setPing] = useState('…');
  useEffect(() => {
    api
      .ping()
      .then((p) => setPing(`服务在线 · uptime ${Math.round(p.uptimeMs / 1000)}s`))
      .catch((e) => setPing('服务未就绪: ' + e.message));
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
        <a
          href="/ble"
          className="block rounded-xl bg-ink-card p-4 active:bg-ink-line"
        >
          <div className="font-medium">BLE / iOS</div>
          <div className="text-sm text-ink-muted">蓝牙 ANCS 采集 iPhone 通知</div>
        </a>
        <div className="block rounded-xl bg-ink-card p-4 opacity-70">
          <div className="font-medium">通知列表</div>
          <div className="text-sm text-ink-muted">原生界面，点顶部状态栏「通知」按钮进入</div>
        </div>
      </div>
    </div>
  );
}
