/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 复刻原 Pages.kt 暗色调
        ink: {
          base: '#14181d',
          card: '#1c2128',
          line: '#2a313b',
          muted: '#8b949e',
          link: '#8fd3ff',
          warn: '#f0b429',
        },
      },
    },
  },
  plugins: [],
};
