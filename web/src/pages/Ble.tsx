import { useCallback, useEffect, useRef, useState } from 'react';

const BASE = 'http://127.0.0.1:8899';

interface BleStatus {
  state?: string;
  isAdvertising?: boolean;
  isConnected?: boolean;
  isSubscribed?: boolean;
  deviceName?: string | null;
  pairedAddress?: string | null;
  pairServiceUuid?: string | null;
  lastError?: string | null;
}

interface BondedDevice {
  name: string;
  address: string;
}

function stateStr(s: BleStatus) {
  if (s.isSubscribed) return { cls: 'text-green-400', text: '● ANCS 已订阅' };
  if (s.isConnected) return { cls: 'text-ink-warn', text: '● 已连接（订阅中）' };
  if (s.isAdvertising) return { cls: 'text-ink-warn', text: '● 广播中（去 iPhone 配对）' };
  return { cls: 'text-red-400', text: '● 空闲' };
}

function timeStr() {
  return new Date().toLocaleTimeString();
}

export default function Ble() {
  const [status, setStatus] = useState<BleStatus>({});
  const [bonded, setBonded] = useState<BondedDevice[]>([]);
  const [logs, setLogs] = useState<string[]>([]);
  const timerRef = useRef<number | null>(null);

  const addLog = useCallback((msg: string, cls?: string) => {
    setLogs((prev) => [...prev.slice(-100), `${timeStr()} ${msg}`]);
  }, []);

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch(`${BASE}/api/ble/status`);
      const s: BleStatus = await res.json();
      setStatus(s);
      const busy = s.isAdvertising || s.isConnected;
      if (s.lastError && s.lastError !== status.lastError) {
        addLog(s.lastError, 'err');
      }
      if (s.isSubscribed) addLog('ANCS 订阅成功，iPhone 通知正在落库');
    } catch (e) {
      // ignore
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const fetchBonded = useCallback(async () => {
    try {
      const res = await fetch(`${BASE}/api/ble/bonded`);
      const list: BondedDevice[] = await res.json();
      setBonded(list || []);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    fetchStatus();
    fetchBonded();
    addLog('BLE 页就绪。先点「开始广播」，再去 iPhone 蓝牙设置里配对。');
    timerRef.current = window.setInterval(fetchStatus, 3000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const pairStart = async () => {
    try {
      const res = await fetch(`${BASE}/api/ble/pair/start`, { method: 'POST' });
      const d = await res.json();
      if (d.error) addLog('广播失败: ' + d.error); else addLog('开始广播，去 iPhone 配对');
      fetchStatus();
    } catch (e: any) {
      addLog('广播请求失败: ' + e.message);
    }
  };

  const pairStop = async () => {
    try {
      await fetch(`${BASE}/api/ble/pair/stop`, { method: 'POST' });
      addLog('已停止广播');
      fetchStatus();
    } catch {
      // ignore
    }
  };

  const doConnect = async (addr?: string) => {
    try {
      const body = addr ? JSON.stringify({ address: addr }) : '{}';
      await fetch(`${BASE}/api/ble/connect`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      });
      addLog('正在连接 iPhone ANCS…');
      fetchStatus();
    } catch {
      // ignore
    }
  };

  const doDisconnect = async () => {
    try {
      await fetch(`${BASE}/api/ble/disconnect`, { method: 'POST' });
      addLog('已断开');
      fetchStatus();
    } catch {
      // ignore
    }
  };

  const busy = status.isAdvertising || status.isConnected;
  const st = stateStr(status);

  return (
    <div className="mx-auto max-w-xl p-3">
      {/* state card */}
      <div className="rounded-xl bg-ink-card p-3 space-y-2">
        <div className="flex justify-between items-center">
          <span className="text-xs text-ink-muted">状态</span>
          <span className={`text-sm font-semibold ${st.cls}`}>{st.text}</span>
        </div>
        <div className="flex justify-between items-center">
          <span className="text-xs text-ink-muted">设备</span>
          <span className="text-sm font-semibold">{status.deviceName || '-'}</span>
        </div>
        <div className="flex justify-between items-center">
          <span className="text-xs text-ink-muted">已配对地址</span>
          <span className="text-xs font-mono text-ink-muted break-all">{status.pairedAddress || '-'}</span>
        </div>
        {status.pairServiceUuid && (
          <div className="flex justify-between items-center">
            <span className="text-xs text-ink-muted">服务 UUID</span>
            <span className="text-xs font-mono text-ink-muted break-all select-all">{status.pairServiceUuid}</span>
          </div>
        )}
      </div>

      {/* error box */}
      {status.lastError && (
        <div className="mt-2 rounded-lg bg-red-900/30 p-2 text-xs text-red-300">
          {status.lastError}
        </div>
      )}

      {/* step 1 */}
      <h2 className="mt-4 mb-1 text-xs text-ink-muted">第 1 步 · 配对（仅首次需要）</h2>
      <p className="text-xs text-ink-muted leading-relaxed mb-2">
        点「开始广播」→ 拿起 <b className="text-ink-link">iPhone</b> 打开{' '}
        <b className="text-ink-link">设置 → 蓝牙</b> → 在列表里找到{' '}
        <b className="text-ink-link">diandi</b>（或本机名）→ 点一下配对。iPhone 弹配对确认就成。
      </p>
      <div className="flex gap-2 flex-wrap">
        {!status.isAdvertising ? (
          <button onClick={pairStart} className="rounded-lg bg-[#2d5a88] px-3 py-2 text-sm text-white">
            开始广播
          </button>
        ) : (
          <button onClick={pairStop} className="rounded-lg border border-ink-line px-3 py-2 text-sm text-ink-muted">
            停止广播
          </button>
        )}
      </div>

      {/* step 2 */}
      <h2 className="mt-4 mb-1 text-xs text-ink-muted">第 2 步 · 连接采集</h2>
      <p className="text-xs text-ink-muted leading-relaxed mb-2">
        配对成功后点「连接 iPhone」。成功后 iPhone 通知会实时进「通知历史」。
      </p>
      <div className="flex gap-2 flex-wrap">
        <button
          onClick={() => doConnect()}
          disabled={busy || !status.pairedAddress}
          className="rounded-lg bg-[#2d5a88] px-3 py-2 text-sm text-white disabled:opacity-40"
        >
          连接 iPhone
        </button>
        {status.isConnected && (
          <button onClick={doDisconnect} className="rounded-lg bg-red-900/60 px-3 py-2 text-sm text-red-200">
            断开
          </button>
        )}
      </div>

      {/* bonded devices */}
      {bonded.length > 0 && (
        <>
          <h2 className="mt-4 mb-1 text-xs text-ink-muted">系统已配对设备（点选连接）</h2>
          <ul className="space-y-1">
            {bonded.map((d) => (
              <li key={d.address} className="flex items-center justify-between rounded-lg bg-[#22262d] px-3 py-2">
                <div className="min-w-0">
                  <div className="text-sm font-medium truncate">{d.name}</div>
                  <div className="text-xs font-mono text-ink-muted">{d.address}</div>
                </div>
                <button
                  onClick={() => doConnect(d.address)}
                  className="ml-2 shrink-0 rounded-lg border border-ink-line px-2 py-1 text-xs text-ink-muted"
                >
                  连接
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      {/* log */}
      <h2 className="mt-4 mb-1 text-xs text-ink-muted">日志</h2>
      <div className="max-h-52 overflow-y-auto rounded-lg bg-[#0d1116] p-2 font-mono text-xs">
        {logs.map((l, i) => (
          <div key={i} className="text-ink-muted/80 mb-0.5">{l}</div>
        ))}
      </div>
    </div>
  );
}
