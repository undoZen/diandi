import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  SafeAreaView,
  StatusBar,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { createApiClient, fmtNotifTime } from '@diandi/shared';
import type { NotificationItem } from '@diandi/shared';

const api = createApiClient('http://127.0.0.1:8899');

type Filter = '' | 'finance';

export default function App() {
  const [filter, setFilter] = useState<Filter>('');
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async (f: Filter) => {
    setLoading(true);
    setErr(null);
    try {
      const rows = await api.notifications({ limit: 200, filter: f || undefined });
      setItems(rows);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(filter);
  }, [filter, load]);

  const renderItem = ({ item }: { item: NotificationItem }) => {
    const body = item.bigText || item.text || item.textLines || '';
    return (
      <View
        style={{
          backgroundColor: '#1c2128',
          borderRadius: 10,
          padding: 12,
          marginHorizontal: 8,
          marginBottom: 8,
          opacity: item.removedAt ? 0.55 : 1,
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', gap: 6 }}>
          <Text style={{ fontSize: 13, fontWeight: '600', color: '#8fd3ff' }}>{item.appName}</Text>
          {item.category ? (
            <View style={{ backgroundColor: '#2d3644', borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 }}>
              <Text style={{ fontSize: 11, color: '#a9c7e8' }}>{item.category}</Text>
            </View>
          ) : null}
          {item.isOngoing ? (
            <View style={{ backgroundColor: '#2d3644', borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 }}>
              <Text style={{ fontSize: 11, color: '#a9c7e8' }}>常驻</Text>
            </View>
          ) : null}
          <Text style={{ marginLeft: 'auto', fontSize: 12, color: '#8b949e' }}>{fmtNotifTime(item.postTime)}</Text>
        </View>

        {item.title ? (
          <Text style={{ marginTop: 4, fontSize: 15, fontWeight: '600', color: '#e6edf3' }}>{item.title}</Text>
        ) : null}
        {body ? (
          <Text style={{ marginTop: 4, fontSize: 14, color: '#e6edf3', opacity: 0.85 }}>{body}</Text>
        ) : null}

        {item.messages && item.messages.length > 0 ? (
          <View style={{ marginTop: 6, borderLeftWidth: 2, borderLeftColor: '#2d3644', paddingLeft: 10 }}>
            {item.messages.map((m, idx) => (
              <Text key={idx} style={{ fontSize: 13, color: '#e6edf3', opacity: 0.9 }}>
                <Text style={{ color: '#a9c7e8' }}>{m.sender || '?'}</Text>
                ：{m.text || ''}
              </Text>
            ))}
          </View>
        ) : null}

        <Text style={{ marginTop: 6, fontSize: 11, color: '#8b949e', fontFamily: 'monospace' }}>{item.packageName}</Text>
      </View>
    );
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: '#14181d' }}>
      <StatusBar barStyle="light-content" />
      <View style={{ backgroundColor: '#1c2128', paddingHorizontal: 12, paddingVertical: 10, flexDirection: 'row', alignItems: 'center' }}>
        <Text style={{ fontSize: 17, fontWeight: '600', color: '#e6edf3' }}>通知历史</Text>
        <Text style={{ marginLeft: 8, fontSize: 13, color: '#8b949e' }}>{items.length} 条</Text>
      </View>

      <View style={{ flexDirection: 'row', gap: 8, padding: 8 }}>
        {(['', 'finance'] as Filter[]).map((f) => (
          <TouchableOpacity
            key={f}
            onPress={() => setFilter(f)}
            style={{
              borderRadius: 999,
              borderWidth: 1,
              borderColor: filter === f ? '#2d5a88' : '#2d3644',
              backgroundColor: filter === f ? '#2d5a88' : 'transparent',
              paddingHorizontal: 14,
              paddingVertical: 6,
            }}
          >
            <Text style={{ fontSize: 13, color: filter === f ? '#fff' : '#a9c7e8' }}>{f === 'finance' ? '财务通知' : '全部'}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {loading ? (
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
          <ActivityIndicator color="#8fd3ff" />
        </View>
      ) : err ? (
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 }}>
          <Text style={{ color: '#e05555', textAlign: 'center' }}>加载失败：{err}</Text>
          <TouchableOpacity onPress={() => load(filter)} style={{ marginTop: 12 }}>
            <Text style={{ color: '#8fd3ff' }}>重试</Text>
          </TouchableOpacity>
        </View>
      ) : items.length === 0 ? (
        <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 }}>
          <Text style={{ color: '#8b949e' }}>还没有通知，等几条进来再刷新</Text>
        </View>
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => String(item.id)}
          renderItem={renderItem}
          contentContainerStyle={{ paddingVertical: 8 }}
        />
      )}
    </SafeAreaView>
  );
}
