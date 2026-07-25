import React, { useCallback, useEffect, useState } from 'react';
import {
  NativeModules,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

const { DiandiSetup } = NativeModules;

interface Status {
  serviceRunning: boolean;
  collectorConnected: boolean;
  notifAccess: boolean;
  postNotifs: boolean;
  batteryIgnored: boolean;
  musicEnabled: boolean;
  lanUrl: string;
  version: string;
  blePairedAddress: string | null;
}

// diandi-ds 暗色色板
const C = {
  bg: '#14181D',
  card: '#1C2128',
  cardHover: '#22262D',
  border: '#2D3644',
  text: '#E8EAED',
  blue: '#4A8EC7',
  amber: '#D4A017',
  green: '#9FD8A8',
  muted: '#8A8F98',
  dim: '#5A5F68',
};

function Dot({ ok }: { ok: boolean | null }) {
  if (ok === null) return <Text style={styles.dotDim}>◐</Text>;
  return <Text style={ok ? styles.dotGreen : styles.dotDim}>{ok ? '●' : '○'}</Text>;
}

function StatusPill({ label, ok }: { label: string; ok: boolean }) {
  return (
    <View style={styles.pill}>
      <Dot ok={ok} />
      <Text style={styles.pillText}>{label}</Text>
    </View>
  );
}

interface GuideItem {
  num: string;
  title: string;
  desc: string;
  ok: boolean | null; // null = 系统不可自动检测，仅引导
  actionLabel: string;
  onAction: () => void;
}

export default function Setup() {
  const [status, setStatus] = useState<Status | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [rebindMsg, setRebindMsg] = useState('');

  const refresh = useCallback(() => {
    DiandiSetup.getStatus()
      .then(setStatus)
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [refresh]);

  const onRebind = () => {
    DiandiSetup.rebindCollector();
    setRebindMsg('已请求重新绑定，请稍候看「采集」圆点…');
    setTimeout(() => setRebindMsg(''), 6000);
  };

  const guides: GuideItem[] = status
    ? [
        {
          num: '①',
          title: '通知使用权',
          desc: status.notifAccess
            ? status.collectorConnected
              ? '采集的入口：读取本机所有通知并落库。已授权，监听器已连接。'
              : '已授权，但系统尚未绑定监听器（重装/重启后常见）。点「重新绑定」通常立即恢复；无效则去系统设置里关掉再打开本 App 的开关。'
            : '采集的入口：读取本机所有通知并落库。不授权则核心功能完全不可用。',
          ok: status.notifAccess
            ? status.collectorConnected
              ? true
              : null
            : false,
          actionLabel: status.notifAccess ? '重新绑定' : '去开启',
          onAction: () =>
            status.notifAccess
              ? onRebind()
              : DiandiSetup.openNotifAccess(),
        },
        {
          num: '②',
          title: '通知权限',
          desc: '前台服务运行时需要显示一条常驻通知（Android 13+ 需手动允许）。',
          ok: status.postNotifs,
          actionLabel: '去开启',
          onAction: () => DiandiSetup.requestPostNotifications(),
        },
        {
          num: '③',
          title: '忽略电池优化',
          desc: '防止系统省电策略杀进程的第一步，一键授权。',
          ok: status.batteryIgnored,
          actionLabel: '去授权',
          onAction: () => DiandiSetup.requestBattery(),
        },
        {
          num: '④',
          title: '自启动 + 后台弹出界面',
          desc: 'ColorOS 关键开关：被杀后自动复活、消费全屏提醒都依赖它。在跳转的页面里把两项都打开。（此项系统没有查询接口，◐ 表示需你手动确认）',
          ok: null,
          actionLabel: '去设置',
          onAction: () => DiandiSetup.openAutoStart(),
        },
      ]
    : [];

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* 头部：名称 + 状态总览 */}
        <Text style={styles.title}>💧 点滴集</Text>
        <Text style={styles.version}>
          v{status?.version ?? '…'} · {status?.lanUrl ?? ''}
        </Text>
        {status && (
          <View style={styles.pillRow}>
            <StatusPill label="服务" ok={status.serviceRunning} />
            <StatusPill label="采集" ok={status.collectorConnected} />
            <StatusPill label="电池" ok={status.batteryIgnored} />
            <StatusPill label="音乐" ok={status.musicEnabled} />
          </View>
        )}

        {/* 基础配置引导 */}
        <Text style={styles.sectionTitle}>基础配置</Text>
        {guides.map((g) => (
          <View key={g.num} style={styles.card}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardNum}>{g.num}</Text>
              <Text style={styles.cardTitle}>{g.title}</Text>
              <Dot ok={g.ok} />
            </View>
            <Text style={styles.cardDesc}>{g.desc}</Text>
            {g.ok !== true && (
              <TouchableOpacity style={styles.cardBtn} onPress={g.onAction}>
                <Text style={styles.cardBtnText}>{g.actionLabel}</Text>
              </TouchableOpacity>
            )}
            {g.num === '①' && rebindMsg !== '' && (
              <Text style={styles.hint}>{rebindMsg}</Text>
            )}
          </View>
        ))}

        {/* 无声音乐保活（开关） */}
        {status && (
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardNum}>⑤</Text>
              <Text style={styles.cardTitle}>无声音乐保活</Text>
              <Switch
                value={status.musicEnabled}
                onValueChange={(v) => {
                  DiandiSetup.setMusicEnabled(v);
                  refresh();
                }}
                trackColor={{ false: C.border, true: C.amber }}
                thumbColor={C.text}
              />
            </View>
            <Text style={styles.cardDesc}>
              循环播放静音音频，让进程处于「音频播放中」状态——系统最不杀的一类进程。
              代价：CPU 不休眠，明显增加耗电。进程频繁被杀时再开。
            </Text>
          </View>
        )}

        {/* 高级功能（默认折叠）：iPhone 采集 */}
        <TouchableOpacity
          style={styles.advHeader}
          onPress={() => setAdvancedOpen(!advancedOpen)}
        >
          <Text style={styles.sectionTitle}>高级功能 {advancedOpen ? '▾' : '▸'}</Text>
        </TouchableOpacity>
        {advancedOpen && status && (
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardTitle}>iPhone 通知采集（BLE / ANCS）</Text>
              <Dot ok={status.blePairedAddress != null} />
            </View>
            <Text style={styles.cardDesc}>
              实验性功能：通过蓝牙 ANCS 协议把 iPhone 的通知也采进同一套管线。
              需要先在系统设置授予蓝牙权限，再在 iPhone「设置→蓝牙」里完成配对。
            </Text>
            <Text style={styles.cardDesc}>
              {status.blePairedAddress
                ? `已配对：${status.blePairedAddress}`
                : '未配对'}
            </Text>
            <View style={styles.advBtnRow}>
              <TouchableOpacity
                style={styles.cardBtn}
                onPress={() => DiandiSetup.openAppDetails()}
              >
                <Text style={styles.cardBtnText}>蓝牙权限</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.cardBtn}
                onPress={() => DiandiSetup.openBlePage()}
              >
                <Text style={styles.cardBtnText}>打开 BLE 管理页</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  scroll: { padding: 16, paddingBottom: 40 },
  title: { fontSize: 24, fontWeight: '700', color: C.text },
  version: { fontSize: 13, color: C.muted, marginTop: 4 },
  pillRow: { flexDirection: 'row', gap: 8, marginTop: 12 },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    backgroundColor: C.card,
    borderRadius: 12,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  pillText: { fontSize: 12, color: C.muted },
  dotGreen: { color: C.green, fontSize: 12 },
  dotDim: { color: C.dim, fontSize: 12 },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: C.muted,
    marginTop: 24,
    marginBottom: 8,
  },
  card: {
    backgroundColor: C.card,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: C.border,
    padding: 14,
    marginBottom: 10,
  },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  cardNum: { fontSize: 15, color: C.amber },
  cardTitle: { flex: 1, fontSize: 15, fontWeight: '600', color: C.text },
  cardDesc: { fontSize: 13, color: C.muted, lineHeight: 19, marginTop: 8 },
  cardBtn: {
    alignSelf: 'flex-start',
    backgroundColor: C.cardHover,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: C.blue,
    paddingHorizontal: 14,
    paddingVertical: 7,
    marginTop: 10,
  },
  cardBtnText: { fontSize: 13, color: C.blue, fontWeight: '600' },
  hint: { fontSize: 12, color: C.amber, marginTop: 8 },
  advHeader: { marginTop: 8 },
  advBtnRow: { flexDirection: 'row', gap: 10 },
});
