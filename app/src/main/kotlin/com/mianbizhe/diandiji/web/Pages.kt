package com.mianbizhe.diandiji.web

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 所有 HTML 页面集中在这里（内联 CSS/JS、无外网 CDN）。
 * 注意：页内 JS 一律不用模板字符串（反引号），避免与 Kotlin 的 $ 插值冲突。
 */
object Pages {

    fun index(lanUrl: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>点滴集</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 0; min-height: 100vh;
                       display: flex; align-items: center; justify-content: center;
                       background: linear-gradient(160deg, #0f2027, #203a43, #2c5364); color: #fff; }
                .card { text-align: center; padding: 2rem 3rem; border-radius: 16px;
                        background: rgba(255,255,255,.08); backdrop-filter: blur(8px); }
                h1 { margin: 0 0 .5rem; font-size: 2.2rem; }
                p  { margin: .3rem 0; opacity: .85; }
                code { background: rgba(255,255,255,.15); padding: .1rem .4rem; border-radius: 6px; }
                a { color: #8fd3ff; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>&#128167; 点滴集</h1>
                <p>Ktor 正在你的手机上运行</p>
                <p>设备：${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})</p>
                <p>局域网访问：<code>$lanUrl</code></p>
                <p>页面生成时间：$now</p>
                <p><a href="/spending">→ 消费</a>　<a href="/dashboard">→ 统计</a>　<a href="/notifications">→ 通知历史</a>　<a href="/ble">→ BLE/iOS</a></p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /** 通知历史列表页：纯静态 HTML + fetch /api/notifications 渲染 */
    fun notifications(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>点滴集 · 通知历史</title>
          <style>
            :root { color-scheme: dark; }
            body { font-family: system-ui, sans-serif; margin: 0; background: #14181d; color: #e8eaed; }
            header { position: sticky; top: 0; z-index: 10; background: #1c2128; padding: .8rem 1rem;
                     display: flex; align-items: baseline; gap: .8rem; box-shadow: 0 1px 4px rgba(0,0,0,.4); }
            header h1 { font-size: 1.1rem; margin: 0; }
            header .count { opacity: .6; font-size: .85rem; }
            header a { margin-left: auto; color: #8fd3ff; font-size: .85rem; text-decoration: none; }
            ul { list-style: none; margin: 0; padding: .5rem; }
            li { background: #1c2128; border-radius: 10px; padding: .7rem .9rem; margin-bottom: .5rem; }
            li.removed { opacity: .55; }
            .row1 { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap; }
            .app { font-weight: 600; font-size: .8rem; color: #8fd3ff; }
            .cat { font-size: .7rem; padding: .05rem .45rem; border-radius: 999px;
                   background: #2d3644; color: #a9c7e8; }
            .time { margin-left: auto; font-size: .75rem; opacity: .55; white-space: nowrap; }
            .title { font-weight: 600; margin-top: .25rem; }
            .text { margin-top: .15rem; font-size: .9rem; opacity: .85; white-space: pre-wrap;
                    word-break: break-word; }
            .msgs { margin-top: .3rem; font-size: .85rem; border-left: 2px solid #2d3644;
                    padding-left: .6rem; opacity: .9; }
            .msgs .sender { color: #a9c7e8; }
            .pkg { margin-top: .35rem; font-size: .7rem; opacity: .45; font-family: monospace;
                   user-select: all; } /* user-select:all 点一下全选，方便复制进白名单 */
            .empty { text-align: center; opacity: .5; padding: 3rem 1rem; }
            nav { display: flex; gap: .5rem; padding: .5rem .5rem 0; }
            nav button { font: inherit; font-size: .8rem; padding: .25rem .8rem; border-radius: 999px;
                         border: 1px solid #2d3644; background: transparent; color: #a9c7e8; }
            nav button.active { background: #2d5a88; border-color: #2d5a88; color: #fff; }
          </style>
        </head>
        <body>
          <header>
            <h1>&#128276; 通知历史</h1>
            <span class="count" id="count"></span>
            <a href="/ble">BLE</a>
            <a href="/spending">消费</a>
          </header>
          <nav id="filters">
            <button data-filter="">全部</button>
            <button data-filter="finance">财务通知</button>
          </nav>
          <ul id="list"></ul>
          <div class="empty" id="empty" hidden>还没有通知，等几条进来再刷新</div>
          <script>
            function esc(s) {
              return String(s).replace(/[&<>"]/g, function (c) {
                return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
              });
            }
            function fmt(ts) {
              var d = new Date(ts), now = new Date();
              var hm = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
              return d.toDateString() === now.toDateString()
                ? hm
                : (d.getMonth() + 1) + '-' + d.getDate() + ' ' + hm;
            }
            function load(filter) {
              document.querySelectorAll('#filters button').forEach(function (b) {
                b.className = b.getAttribute('data-filter') === filter ? 'active' : '';
              });
              try { localStorage.setItem('filter', filter); } catch (e) {}
              fetch('/api/notifications?limit=200' + (filter ? '&filter=' + filter : ''))
                .then(function (r) { return r.json(); })
                .then(function (items) {
                  document.getElementById('count').textContent = '最近 ' + items.length + ' 条';
                  document.getElementById('empty').hidden = items.length > 0;
                  var html = '';
                  items.forEach(function (n) {
                    var body = n.bigText || n.text || n.textLines || '';
                    html += '<li' + (n.removedAt ? ' class="removed"' : '') + '>'
                      + '<div class="row1">'
                      + '<span class="app">' + esc(n.appName) + '</span>'
                      + (n.category ? '<span class="cat">' + esc(n.category) + '</span>' : '')
                      + (n.isOngoing ? '<span class="cat">常驻</span>' : '')
                      + '<span class="time">' + fmt(n.postTime) + '</span>'
                      + '</div>'
                      + (n.title ? '<div class="title">' + esc(n.title) + '</div>' : '')
                      + (body ? '<div class="text">' + esc(body) + '</div>' : '');
                    if (n.messages && n.messages.length) {
                      html += '<div class="msgs">';
                      n.messages.forEach(function (m) {
                        html += '<div><span class="sender">' + esc(m.sender || '?') + '</span>：'
                          + esc(m.text || '') + '</div>';
                      });
                      html += '</div>';
                    }
                    html += '<div class="pkg">' + esc(n.packageName) + '</div>';
                    html += '</li>';
                  });
                  document.getElementById('list').innerHTML = html;
                });
            }
            document.querySelectorAll('#filters button').forEach(function (b) {
              b.addEventListener('click', function () { load(b.getAttribute('data-filter')); });
            });
            var saved = 'finance'; // 默认财务组；用户切换过则记住上次选择
            try { saved = localStorage.getItem('filter') !== null ? localStorage.getItem('filter') : 'finance'; } catch (e) {}
            load(saved);
          </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * 消费页：按日分组 + 类别 chip；点 chip 弹底部选择层改类别（POST 修正）。
     * uncertain 行琥珀虚线高亮，corrected 行 ✓ 前缀。
     */
    fun spending(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>点滴集 · 消费</title>
          <style>
            :root { color-scheme: dark; }
            body { font-family: system-ui, sans-serif; margin: 0; background: #14181d; color: #e8eaed; }
            header { position: sticky; top: 0; z-index: 10; background: #1c2128; padding: .8rem 1rem;
                     display: flex; align-items: baseline; gap: .8rem; box-shadow: 0 1px 4px rgba(0,0,0,.4); }
            header h1 { font-size: 1.1rem; margin: 0; }
            header .count { opacity: .6; font-size: .85rem; }
            header a { margin-left: auto; color: #8fd3ff; font-size: .85rem; text-decoration: none; }
            .day { position: sticky; top: 3.2rem; z-index: 5; background: #14181d; padding: .55rem .9rem .3rem;
                   font-size: .8rem; opacity: .75; display: flex; justify-content: space-between; }
            ul { list-style: none; margin: 0; padding: 0 .5rem; }
            li { background: #1c2128; border-radius: 10px; padding: .6rem .9rem; margin-bottom: .45rem;
                 display: flex; align-items: center; gap: .6rem; }
            li.divider { background: transparent; border-radius: 0; padding: .8rem 0 .3rem; margin: 0;
                         display: block; text-align: center; font-size: .72rem; opacity: .45;
                         border-bottom: 1px solid #2d3644; }
            .info { flex: 1; min-width: 0; }
            .merchant { font-size: .92rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis;
                        white-space: nowrap; }
            .merchant.none { opacity: .45; font-weight: 400; }
            .meta { font-size: .72rem; opacity: .5; margin-top: .15rem; }
            .amount { font-size: 1rem; font-weight: 700; font-variant-numeric: tabular-nums;
                      white-space: nowrap; }
            .chip { font: inherit; font-size: .75rem; padding: .2rem .6rem; border-radius: 999px;
                    border: 1px solid #2d5a88; background: #22364a; color: #a9c7e8; white-space: nowrap; }
            .chip.uncertain { border: 1px dashed #d4a017; background: #3a3220; color: #e8c96a; }
            .chip.corrected { border-color: #3a7d44; background: #24382a; color: #9fd8a8; }
            .empty { text-align: center; opacity: .5; padding: 3rem 1rem; }
            #sheetWrap { position: fixed; inset: 0; z-index: 100; display: none; }
            #sheetWrap.open { display: block; }
            #backdrop { position: absolute; inset: 0; background: rgba(0,0,0,.55); }
            #sheet { position: absolute; left: 0; right: 0; bottom: 0; background: #1c2128;
                     border-radius: 16px 16px 0 0; padding: 1rem 1rem 1.5rem; max-height: 70vh; overflow-y: auto; }
            #sheet h2 { font-size: .95rem; margin: 0 0 .3rem; }
            #sheet .target { font-size: .8rem; opacity: .6; margin-bottom: .8rem; }
            .cand { margin-bottom: .8rem; }
            .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: .5rem; }
            .grid button { font: inherit; font-size: .85rem; padding: .55rem .3rem; border-radius: 10px;
                           border: 1px solid #2d3644; background: #22262d; color: #e8eaed; }
            .grid button.cur { border-color: #2d5a88; background: #22364a; }
            .grid button .p { display: block; font-size: .68rem; opacity: .55; margin-top: .1rem; }
          </style>
        </head>
        <body>
          <header>
            <h1>&#128176; 消费</h1>
            <span class="count" id="count"></span>
            <a href="#" id="btnManual" style="color:#d4a017;font-weight:600;text-decoration:none">＋录入</a>
            <a href="/dashboard">统计</a>
            <a href="/ble">BLE</a>
            <a href="/notifications" style="margin-left:.8rem">通知</a>
          </header>
          <div id="days"></div>
          <div class="empty" id="empty" hidden>暂无消费记录（等一条银行通知，或检查通知使用权）</div>

          <div id="sheetWrap">
            <div id="backdrop"></div>
            <div id="sheet">
              <h2>选择类别</h2>
              <div class="target" id="sheetTarget"></div>
              <div class="grid" id="sheetGrid"></div>
            </div>
          </div>

          <div id="manualWrap" style="position:fixed;inset:0;z-index:100;display:none">
            <div id="manualBackdrop" style="position:absolute;inset:0;background:rgba(0,0,0,.55)"></div>
            <div style="position:absolute;left:0;right:0;bottom:0;background:#1c2128;
                        border-radius:16px 16px 0 0;padding:1rem 1rem 1.5rem">
              <h2 style="font-size:.95rem;margin:0 0 .5rem">手动录入消费</h2>
              <p style="font-size:.75rem;opacity:.5;margin:0 0 .6rem">
                粘贴银行通知原文（工行/招行/掌上生活），自动解析金额和商户</p>
              <textarea id="manualInput" rows="4" style="width:100%;box-sizing:border-box;
                background:#14181d;color:#e8eaed;border:1px solid #2d3644;border-radius:8px;
                padding:.5rem;font:inherit;font-size:.85rem;resize:vertical"
                placeholder="例如：尾号0000卡7月6日11:36支出(消费美团支付-美团App德克士Belray咖啡)36.30元。"></textarea>
              <p id="manualError" style="font-size:.75rem;color:#e05555;margin:.4rem 0 0;display:none"></p>
              <div style="display:flex;gap:.5rem;margin-top:.8rem">
                <button id="manualSubmit" style="flex:1;font:inherit;font-size:.9rem;padding:.5rem;
                  border:none;border-radius:8px;background:#d4a017;color:#000;font-weight:600">录入</button>
                <button id="manualCancel" style="flex:1;font:inherit;font-size:.9rem;padding:.5rem;
                  border:1px solid #2d3644;border-radius:8px;background:transparent;color:#e8eaed">取消</button>
              </div>
            </div>
          </div>

          <script>
            var CATS = [];
            var rows = [];
            var sheetRow = null;
            // 未读水位：上次访问时间存 localStorage；本次会话内新看到的行保持高亮
            // （高亮以"进入页面那一刻"为准，浏览过程中不消失，下次进来才归零）
            var lastSeen = 0;
            try { lastSeen = +localStorage.getItem('spendingSeenAt') || 0; } catch (e) {}
            function touchSeen() {
              try { localStorage.setItem('spendingSeenAt', String(Date.now())); } catch (e) {}
            }

            function esc(s) {
              return String(s).replace(/[&<>"]/g, function (c) {
                return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
              });
            }
            function yuan(cents) { return (cents / 100).toFixed(2); }
            function hm(ts) {
              var d = new Date(ts);
              return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
            }
            function dayKey(ts) {
              var d = new Date(ts);
              return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
            }
            function dayLabel(ts) {
              var d = new Date(ts);
              return (d.getMonth() + 1) + '月' + d.getDate() + '日';
            }

            function chipClass(r) {
              if (r.corrected) return 'chip corrected';
              if (r.uncertain) return 'chip uncertain';
              return 'chip';
            }
            function chipText(r) {
              var t = r.category || 'Unknown';
              if (r.corrected) return '✓ ' + t;
              if (r.uncertain) return t + ' ?';
              return t;
            }

            function render() {
              document.getElementById('count').textContent = rows.length + ' 笔';
              document.getElementById('empty').hidden = rows.length > 0;
              var newCount = 0;
              var groups = {};
              var order = [];
              rows.forEach(function (r) {
                var k = dayKey(r.txTime);
                if (!groups[k]) { groups[k] = []; order.push(k); }
                groups[k].push(r);
                if (r.createdAt > lastSeen) newCount++;
              });
              document.getElementById('count').textContent = rows.length + ' 笔'
                + (newCount > 0 ? ' · ' + newCount + ' 新' : '');
              var html = '';
              var shownDivider = false;
              order.forEach(function (k) {
                var g = groups[k];
                var sum = 0;
                g.forEach(function (r) { sum += r.amountCents; });
                html += '<div class="day"><span>' + dayLabel(g[0].txTime) + ' · ' + g.length + '笔</span>'
                  + '<span>¥' + yuan(sum) + '</span></div><ul>';
                g.forEach(function (r) {
                  var isNew = r.createdAt > lastSeen;
                  // 从第一条旧记录开始插入分割线（只一次）
                  if (!isNew && !shownDivider) {
                    shownDivider = true;
                    html += '<li class="divider">以下记录已经确认处理过</li>';
                  }
                  var m = r.merchant
                    ? '<div class="merchant">' + esc(r.merchant) + '</div>'
                    : '<div class="merchant none">（无商户）</div>';
                  var meta = hm(r.txTime) + (r.channel ? ' · ' + esc(r.channel) : '');
                  html += '<li data-id="' + r.id + '">'
                    + '<div class="info">' + m + '<div class="meta">' + meta + '</div></div>'
                    + '<button class="' + chipClass(r) + '" data-id="' + r.id + '">' + esc(chipText(r)) + '</button>'
                    + '<span class="amount">¥' + yuan(r.amountCents) + '</span>'
                    + '</li>';
                });
                html += '</ul>';
              });
              document.getElementById('days').innerHTML = html;
              document.querySelectorAll('.chip').forEach(function (b) {
                b.addEventListener('click', function () { openSheet(+b.getAttribute('data-id')); });
              });
            }

            function openSheet(id) {
              sheetRow = rows.find(function (r) { return r.id === id; });
              if (!sheetRow) return;
              document.getElementById('sheetTarget').textContent =
                (sheetRow.merchant || '（无商户）') + ' · ¥' + yuan(sheetRow.amountCents);
              var probs = {};
              (sheetRow.candidates || []).forEach(function (c) { probs[c.category] = c.prob; });
              // 候选置顶，其余类别按原序
              var cats = Object.keys(probs).concat(CATS.filter(function (c) { return !(c in probs); }));
              var html = '';
              cats.forEach(function (c) {
                html += '<button data-cat="' + esc(c) + '"'
                  + (c === sheetRow.category ? ' class="cur"' : '') + '>'
                  + esc(c)
                  + (probs[c] != null ? '<span class="p">' + (probs[c] * 100).toFixed(0) + '%</span>' : '')
                  + '</button>';
              });
              document.getElementById('sheetGrid').innerHTML = html;
              document.querySelectorAll('#sheetGrid button').forEach(function (b) {
                b.addEventListener('click', function () { correct(b.getAttribute('data-cat')); });
              });
              document.getElementById('sheetWrap').className = 'open';
            }
            function closeSheet() {
              document.getElementById('sheetWrap').className = '';
              sheetRow = null;
            }
            document.getElementById('backdrop').addEventListener('click', closeSheet);

            function correct(cat) {
              if (!sheetRow) return;
              var row = sheetRow;
              var old = { category: row.category, corrected: row.corrected, uncertain: row.uncertain };
              row.category = cat; row.corrected = true; row.uncertain = false;
              render(); // 乐观更新
              closeSheet();
              fetch('/api/spending/' + row.id + '/category', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ category: cat }),
              }).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                load(); // 拉最新：修正结果 + 期间可能新到的消费
              }).catch(function () {
                row.category = old.category; row.corrected = old.corrected; row.uncertain = old.uncertain;
                render(); // 失败回滚
                alert('保存失败，请重试');
              });
            }

            function load() {
              return fetch('/api/spending?limit=300')
                .then(function (r) { return r.json(); })
                .then(function (items) { rows = items; render(); });
            }

            // 回到前台（WebView 切回 / 浏览器切标签页）自动刷新——
            // 弹完全屏提醒回来、锁屏解锁回来，列表都直接是最新的。
            // 刷新前不动 lastSeen：新来的记录照样标「新」，
            // 离开页面时才把水位推到现在（下次进来重新计算未读）。
            document.addEventListener('visibilitychange', function () {
              if (document.visibilityState === 'visible') { load(); }
              else { touchSeen(); }
            });
            window.addEventListener('pagehide', touchSeen);

            // ---- 手动录入 ----
            var manualWrap = document.getElementById('manualWrap');
            var manualInput = document.getElementById('manualInput');
            var manualError = document.getElementById('manualError');
            document.getElementById('btnManual').addEventListener('click', function (e) {
              e.preventDefault();
              manualError.style.display = 'none';
              manualInput.value = '';
              manualWrap.style.display = 'block';
              manualInput.focus();
            });
            document.getElementById('manualBackdrop').addEventListener('click', function () {
              manualWrap.style.display = 'none';
            });
            document.getElementById('manualCancel').addEventListener('click', function () {
              manualWrap.style.display = 'none';
            });
            document.getElementById('manualSubmit').addEventListener('click', function () {
              var text = manualInput.value.trim();
              if (!text) { showManualError('请粘贴银行通知文本'); return; }
              var btn = this;
              btn.disabled = true; btn.textContent = '解析中…';
              fetch('/api/spending/manual', {
                method: 'POST', body: text,
              }).then(function (r) {
                return r.json().then(function (data) {
                  if (!r.ok) { showManualError(data.error || '录入失败'); return; }
                  manualWrap.style.display = 'none';
                  load();
                });
              }).catch(function () {
                showManualError('网络错误，请稍后重试');
              }).finally(function () {
                btn.disabled = false; btn.textContent = '录入';
              });
            });
            function showManualError(msg) {
              manualError.textContent = msg;
              manualError.style.display = 'block';
            }

            fetch('/api/spending/categories')
              .then(function (r) { return r.json(); })
              .then(function (c) { CATS = c; return load(); });
          </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * 统计 dashboard：时间范围切换（7/30/90 天）+ 分类占比条 + 按日趋势条。
     * 纯 CSS 渲染无图表库；手机 WebView 和桌面浏览器（局域网）通用。
     */
    fun dashboard(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>点滴集 · 消费统计</title>
          <style>
            :root { color-scheme: dark; }
            body { font-family: system-ui, sans-serif; margin: 0; background: #14181d; color: #e8eaed; }
            header { position: sticky; top: 0; z-index: 10; background: #1c2128; padding: .8rem 1rem;
                     display: flex; align-items: baseline; gap: .8rem; box-shadow: 0 1px 4px rgba(0,0,0,.4); }
            header h1 { font-size: 1.1rem; margin: 0; }
            header a { margin-left: auto; color: #8fd3ff; font-size: .85rem; text-decoration: none; }
            .wrap { max-width: 720px; margin: 0 auto; padding: .8rem; }
            nav { display: flex; gap: .5rem; margin-bottom: 1rem; }
            nav button { font: inherit; font-size: .8rem; padding: .25rem .8rem; border-radius: 999px;
                         border: 1px solid #2d3644; background: transparent; color: #a9c7e8; }
            nav button.active { background: #2d5a88; border-color: #2d5a88; color: #fff; }
            .total { text-align: center; margin: 1.2rem 0 1.6rem; }
            .total .num { font-size: 2rem; font-weight: 700; font-variant-numeric: tabular-nums; }
            .total .sub { font-size: .8rem; opacity: .55; margin-top: .2rem; }
            h2 { font-size: .85rem; opacity: .7; margin: 1.4rem 0 .6rem; }
            .catrow { display: flex; align-items: center; gap: .6rem; margin-bottom: .55rem; }
            .catrow .name { width: 5.5em; font-size: .82rem; text-align: right; flex-shrink: 0; }
            .catrow .bar { flex: 1; height: 1.15rem; background: #1c2128; border-radius: 6px; overflow: hidden; }
            .catrow .fill { height: 100%; border-radius: 6px; background: linear-gradient(90deg, #2d5a88, #4a8ec7); }
            .catrow .val { width: 6.5em; font-size: .78rem; font-variant-numeric: tabular-nums;
                           flex-shrink: 0; opacity: .85; }
            .catrow .pct { opacity: .5; }
            .days { display: flex; gap: 3px; margin-top: .8rem; }
            .dcol { flex: 1; position: relative; min-width: 0;
                    padding-bottom: 16px; /* 给标签留固定空间 */ }
            .dbarSpace { width: 100%; height: 100px; display: flex; align-items: flex-end;
                         justify-content: center; }
            .dbar { width: 70%; max-width: 26px; background: linear-gradient(180deg, #d4a017, #a87c0e);
                    border-radius: 4px 4px 0 0; min-height: 2px; }
            .dlabel { position: absolute; bottom: 0; left: 0; right: 0;
                      font-size: .58rem; opacity: .45; white-space: nowrap; text-align: center; }
            .empty { text-align: center; opacity: .5; padding: 3rem 1rem; }
          </style>
        </head>
        <body>
          <header>
            <h1>&#128202; 消费统计</h1>
            <a href="/spending">消费</a>
          </header>
          <div class="wrap">
            <nav id="ranges">
              <button data-days="7">7 天</button>
              <button data-days="30">30 天</button>
              <button data-days="90">90 天</button>
            </nav>
            <div class="total"><div class="num" id="total"></div><div class="sub" id="totalSub"></div></div>
            <div id="cats"></div>
            <h2 id="trendTitle" hidden>按日趋势</h2>
            <div class="days" id="days"></div>
            <div class="empty" id="empty" hidden>该时间段没有消费记录</div>
          </div>
          <script>
            function esc(s) {
              return String(s).replace(/[&<>"]/g, function (c) {
                return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
              });
            }
            function yuan(cents) { return (cents / 100).toFixed(2); }
            function dayKey(ts) {
              var d = new Date(ts);
              return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
            }

            function load(days) {
              document.querySelectorAll('#ranges button').forEach(function (b) {
                b.className = +b.getAttribute('data-days') === days ? 'active' : '';
              });
              try { localStorage.setItem('dashDays', String(days)); } catch (e) {}
              fetch('/api/spending?days=' + days + '&limit=500')
                .then(function (r) { return r.json(); })
                .then(function (items) { render(items, days); });
            }

            function render(items, days) {
              var total = 0;
              var byCat = {};
              var byDay = {};
              items.forEach(function (r) {
                total += r.amountCents;
                var c = r.category || 'Unknown';
                byCat[c] = (byCat[c] || 0) + r.amountCents;
                var k = dayKey(r.txTime);
                byDay[k] = (byDay[k] || 0) + r.amountCents;
              });

              document.getElementById('empty').hidden = items.length > 0;
              document.getElementById('total').textContent = '¥' + yuan(total);
              document.getElementById('totalSub').textContent =
                items.length + ' 笔 · 日均 ¥' + yuan(Math.round(total / days));

              // 分类占比（按金额降序）
              var cats = Object.keys(byCat).sort(function (a, b) { return byCat[b] - byCat[a]; });
              var maxCat = cats.length ? byCat[cats[0]] : 1;
              var html = cats.length ? '<h2>分类占比</h2>' : '';
              cats.forEach(function (c) {
                var v = byCat[c];
                var pct = total ? (v * 100 / total).toFixed(0) : 0;
                html += '<div class="catrow">'
                  + '<span class="name">' + esc(c) + '</span>'
                  + '<div class="bar"><div class="fill" style="width:' + (v * 100 / maxCat) + '%"></div></div>'
                  + '<span class="val">¥' + yuan(v) + ' <span class="pct">' + pct + '%</span></span>'
                  + '</div>';
              });
              document.getElementById('cats').innerHTML = html;

              // 按日趋势：连续日期轴（无消费的日补 0），最多显示最近 30 根
              var barsWanted = Math.min(days, 30);
              var dhtml = '';
              var maxDay = 1;
              var keys = [];
              for (var i = barsWanted - 1; i >= 0; i--) {
                var d = new Date(Date.now() - i * 86400000);
                var k = dayKey(d.getTime());
                keys.push(k);
                if ((byDay[k] || 0) > maxDay) maxDay = byDay[k];
              }
              keys.forEach(function (k, idx) {
                var v = byDay[k] || 0;
                var h = v ? Math.max(3, v * 100 / maxDay) : 0;
                // 标签：稀疏显示防挤（首/尾/每 5 根）
                var label = (idx === 0 || idx === keys.length - 1 || idx % 5 === 0)
                  ? k.slice(5).replace('-', '/') : '';
                dhtml += '<div class="dcol">'
                  + '<div class="dbarSpace"><div class="dbar" style="height:' + h + '%"></div></div>'
                  + '<span class="dlabel">' + label + '</span>'
                  + '</div>';
              });
              document.getElementById('trendTitle').hidden = items.length === 0;
              document.getElementById('days').innerHTML = dhtml;
            }

            document.querySelectorAll('#ranges button').forEach(function (b) {
              b.addEventListener('click', function () { load(+b.getAttribute('data-days')); });
            });
            // 回到前台自动刷新（和 /spending 页同款）
            var curDays = 30;
            try { curDays = +localStorage.getItem('dashDays') || 30; } catch (e) {}
            document.addEventListener('visibilitychange', function () {
              if (document.visibilityState === 'visible') load(curDays);
            });
            document.querySelectorAll('#ranges button').forEach(function (b) {
              b.addEventListener('click', function () { curDays = +b.getAttribute('data-days'); });
            });
            load(curDays);
          </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * BLE ANCS 控制页：扫描 → 选择 iPhone → 连接 → 观察通知到达。
     * 纯 API 驱动 `/api/ble/` 端点，不依赖任何库。
     */
    fun bleControl(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>点滴集 · BLE / iPhone</title>
          <style>
            :root { color-scheme: dark; }
            body { font-family: system-ui, sans-serif; margin: 0; background: #14181d; color: #e8eaed; }
            header { position: sticky; top: 0; z-index: 10; background: #1c2128; padding: .8rem 1rem;
                     display: flex; align-items: baseline; gap: .8rem; box-shadow: 0 1px 4px rgba(0,0,0,.4); }
            header h1 { font-size: 1.1rem; margin: 0; }
            header a { margin-left: auto; color: #8fd3ff; font-size: .85rem; text-decoration: none; }
            .wrap { max-width: 600px; margin: 0 auto; padding: .8rem; }
            .card { background: #1c2128; border-radius: 10px; padding: .7rem .9rem; margin-bottom: .5rem; }
            .row { display: flex; justify-content: space-between; align-items: center; gap: .6rem; }
            .label { opacity: .65; font-size: .85rem; }
            .value { font-weight: 600; }
            .ok { color: #8fd8a8; }
            .warn { color: #d4a017; }
            .off { color: #e05555; }
            button { font: inherit; font-size: .85rem; padding: .4rem .9rem; border-radius: 8px; border: none; }
            .btn-primary { background: #2d5a88; color: #fff; }
            .btn-primary:disabled { opacity: .4; }
            .btn-danger { background: #5a2d2d; color: #e8a8a8; }
            .btn-outline { background: transparent; border: 1px solid #2d3644; color: #a9c7e8; }
            h2 { font-size: .85rem; opacity: .7; margin: 1rem 0 .4rem; }
            .devlist { list-style: none; margin: 0; padding: 0; }
            .devlist li { padding: .5rem .7rem; border-radius: 8px; background: #22262d; margin-bottom: .3rem;
                         display: flex; justify-content: space-between; align-items: center; }
            .devlist li .name { font-weight: 500; }
            .devlist li .addr { font-size: .7rem; opacity: .5; font-family: monospace; }
            .log { margin-top: .8rem; background: #0d1116; border-radius: 8px; padding: .5rem .7rem;
                   font-family: monospace; font-size: .75rem; max-height: 200px; overflow-y: auto; }
            .log div { opacity: .75; margin-bottom: .2rem; }
            .log .evt { color: #8fd3ff; }
            .log .err { color: #e05555; }
            .log .ok { color: #8fd8a8; }
            .step { font-size: .8rem; opacity: .8; line-height: 1.5; margin: .3rem 0 .6rem; }
            .step b { color: #8fd3ff; }
            .errbox { background: #3a2222; color: #e8a8a8; border-radius: 8px; padding: .5rem .7rem; font-size: .8rem; margin-bottom: .5rem; }
          </style>
        </head>
        <body>
          <header>
            <h1>&#128293; BLE / iPhone</h1>
            <a href="/spending">消费</a>
          </header>
          <div class="wrap">
            <!-- 状态 -->
            <div class="card">
              <div class="row"><span class="label">状态</span><span class="value" id="bleState">检查中…</span></div>
              <div class="row" style="margin-top:.4rem"><span class="label">设备</span><span class="value" id="bleDevice">-</span></div>
              <div class="row" style="margin-top:.4rem"><span class="label">已配对地址</span><span class="value" id="blePaired" style="font-family:monospace;font-size:.75rem">-</span></div>
            </div>
            <div id="errbox" class="errbox" hidden></div>

            <!-- 第一步：广播配对 -->
            <h2>第 1 步 · 配对（仅首次需要）</h2>
            <div class="step">
              点「开始广播」→ 拿起 <b>iPhone</b> 打开 <b>设置 → 蓝牙</b>→ 在列表里找到
              <b>diandi</b>（或本机名）→ 点一下配对。iPhone 弹配对确认就成。
            </div>
            <div style="display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:.5rem">
              <button class="btn-primary" id="btnPair" onclick="pairStart()">开始广播</button>
              <button class="btn-outline" id="btnPairStop" onclick="pairStop()" hidden>停止广播</button>
            </div>

            <!-- 第二步：连接 ANCS -->
            <h2>第 2 步 · 连接采集</h2>
            <div class="step">
              配对成功后点「连接 iPhone」。成功后 iPhone 通知会实时进「通知历史」。
            </div>
            <div style="display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:.5rem">
              <button class="btn-primary" id="btnConn" onclick="connect()">连接 iPhone</button>
              <button class="btn-danger" id="btnDisc" onclick="disconnect()" hidden>断开</button>
            </div>

            <!-- 已配对设备（兜底手动选） -->
            <div id="bondedSection" hidden>
              <h2>系统已配对设备（点选连接）</h2>
              <ul class="devlist" id="bondlist"></ul>
            </div>

            <h2>日志</h2>
            <div class="log" id="log"></div>
          </div>
          <script>
            function log(msg, cls) {
              var div = document.createElement('div');
              div.textContent = new Date().toLocaleTimeString() + ' ' + msg;
              if (cls) div.className = cls;
              var el = document.getElementById('log');
              el.appendChild(div); el.scrollTop = el.scrollHeight;
            }
            function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g,'?'); }

            function stateStr(s) {
              if (s.isSubscribed)  return '<span class="value ok">&#9679; ANCS 已订阅</span>';
              if (s.isConnected)   return '<span class="value warn">&#9679; 已连接（订阅中）</span>';
              if (s.isAdvertising) return '<span class="value warn">&#9679; 广播中（去 iPhone 配对）</span>';
              return '<span class="value off">&#9679; 空闲</span>';
            }

            function fetchStatus() {
              fetch('/api/ble/status').then(function (r) { return r.json(); }).then(function (s) {
                document.getElementById('bleState').innerHTML = stateStr(s);
                document.getElementById('bleDevice').textContent = s.deviceName || '-';
                document.getElementById('blePaired').textContent = s.pairedAddress || '-';
                var eb = document.getElementById('errbox');
                if (s.lastError) { eb.textContent = s.lastError; eb.hidden = false; } else { eb.hidden = true; }
                var busy = s.isAdvertising || s.isConnected;
                document.getElementById('btnPair').hidden = s.isAdvertising;
                document.getElementById('btnPairStop').hidden = !s.isAdvertising;
                document.getElementById('btnConn').disabled = busy || !s.pairedAddress;
                document.getElementById('btnDisc').hidden = !s.isConnected;
                if (s.isSubscribed) log('ANCS 订阅成功，iPhone 通知正在落库', 'ok');
              }).catch(function (e) { log('status fetch failed: ' + e.message, 'err'); });
            }

            function pairStart() {
              fetch('/api/ble/pair/start', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (d) {
                if (d.error) log('广播失败: ' + d.error, 'err'); else log('开始广播，去 iPhone 配对', 'evt');
                fetchStatus();
              });
            }
            function pairStop() {
              fetch('/api/ble/pair/stop', { method: 'POST' }).then(function () { log('已停止广播', 'evt'); fetchStatus(); });
            }
            function connect(addr) {
              var body = addr ? JSON.stringify({ address: addr }) : '{}';
              fetch('/api/ble/connect', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body })
                .then(function (r) { return r.json(); }).then(function () {
                  log('正在连接 iPhone ANCS…', 'evt'); fetchStatus();
                });
            }
            function disconnect() {
              fetch('/api/ble/disconnect', { method: 'POST' }).then(function () { log('已断开', 'evt'); fetchStatus(); });
            }

            function fetchBonded() {
              fetch('/api/ble/bonded').then(function (r) { return r.json(); }).then(function (list) {
                if (!list || !list.length) { document.getElementById('bondedSection').hidden = true; return; }
                document.getElementById('bondedSection').hidden = false;
                var html = '';
                list.forEach(function (d) {
                  html += '<li><div><div class="name">' + esc(d.name) + '</div>'
                    + '<div class="addr">' + esc(d.address) + '</div></div>'
                    + '<button class="btn-outline" onclick="connect(\'' + d.address + '\')">连接</button></li>';
                });
                document.getElementById('bondlist').innerHTML = html;
              });
            }

            setInterval(fetchStatus, 3000);
            fetchStatus(); fetchBonded();
            log('BLE 页就绪。先点「开始广播」，再去 iPhone 蓝牙设置里配对。', 'evt');
          </script>
        </body>
        </html>
    """.trimIndent()
}
