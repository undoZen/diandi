import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';
import { consumeUrlSpend, init } from './core/agent';
import { startAndroidSync } from './core/android';

init();
consumeUrlSpend();
startAndroidSync();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
