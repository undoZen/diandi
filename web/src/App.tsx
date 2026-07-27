import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import Spending from './pages/Spending';
import Dashboard from './pages/Dashboard';
import Ble from './pages/Ble';
import { isInApp } from './lib/env';

/**
 * 底部三 tab：消费 / 统计 / 设置。
 * 「设置」在 App 内是 /native/setup 链接（WebView 拦截拉起 RN 设置屏），
 * 浏览器访问时没有原生能力，不渲染该 tab。
 * tab 切换用 replace：不产生浏览器历史，配合原生返回键直接退出（App 逻辑）。
 */
function TabBar() {
  const cls = ({ isActive }: { isActive: boolean }) =>
    `flex flex-1 flex-col items-center gap-0.5 py-2 text-[0.7rem] transition-colors ${
      isActive ? 'text-ink-warn' : 'text-ink-muted'
    }`;
  return (
    <nav className="fixed inset-x-0 bottom-0 z-10 flex border-t border-ink-line bg-ink-card/95 backdrop-blur">
      <NavLink to="/spending" replace className={cls}>
        <span className="text-base leading-none">💸</span>消费
      </NavLink>
      <NavLink to="/dashboard" replace className={cls}>
        <span className="text-base leading-none">📊</span>统计
      </NavLink>
      {isInApp && (
        <a
          href="/native/setup"
          className="flex flex-1 flex-col items-center gap-0.5 py-2 text-[0.7rem] text-ink-muted"
        >
          <span className="text-base leading-none">⚙️</span>设置
        </a>
      )}
      {/* BLE 入口收进「设置 → 高级功能」，/ble 路由保留 */}
    </nav>
  );
}

export default function App() {
  return (
    <div className="min-h-full">
      {/* 底部 tab 栏占位，避免列表最后一条被遮住 */}
      <div className="pb-14">
        <Routes>
          <Route path="/" element={<Navigate to="/spending" replace />} />
          <Route path="/spending" element={<Spending />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/ble" element={<Ble />} />
        </Routes>
      </div>
      <TabBar />
    </div>
  );
}
