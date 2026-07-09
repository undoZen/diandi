import { NavLink, Route, Routes } from 'react-router-dom';
import Home from './pages/Home';
import Spending from './pages/Spending';
import Dashboard from './pages/Dashboard';
import Ble from './pages/Ble';

function NavBar() {
  const cls = ({ isActive }: { isActive: boolean }) =>
    `px-2 py-2 rounded-lg text-xs transition-colors ${
      isActive ? 'bg-ink-line text-white' : 'text-ink-muted'
    }`;
  return (
    <nav className="sticky top-0 z-10 border-b border-ink-line bg-ink-card/95 backdrop-blur">
      <div className="flex gap-1 px-3 py-2 overflow-x-auto">
        <NavLink to="/" end className={cls}>首页</NavLink>
        <NavLink to="/spending" className={cls}>消费</NavLink>
        <NavLink to="/dashboard" className={cls}>统计</NavLink>
        <NavLink to="/ble" className={cls}>BLE</NavLink>
      </div>
    </nav>
  );
}

export default function App() {
  return (
    <div className="min-h-full">
      <NavBar />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/spending" element={<Spending />} />
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/ble" element={<Ble />} />
      </Routes>
    </div>
  );
}
