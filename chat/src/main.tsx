import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as CapApp } from '@capacitor/app';
import App from './App';
import './index.css';
import { checkRecentScreenshot, consumeUrlSpend, init } from './core/agent';
import { startAndroidSync } from './core/android';
import { isNativeIOS } from './core/ocr';

init();
consumeUrlSpend();
startAndroidSync();
if (isNativeIOS()) {
  // 像微信「你可能要发送的照片」：打开或回到前台时，看 1 分钟内有没有新截图
  checkRecentScreenshot();
  CapApp.addListener('appStateChange', ({ isActive }) => { if (isActive) checkRecentScreenshot(); });
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
