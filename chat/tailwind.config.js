/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        paper: '#f6f3ee',
        ink: '#2b2722',
        muted: '#8a8178',
        line: '#e6dfd5',
        warm: '#e8796a',
        roast: '#d9480f',
        leaf: '#3f8f6b',
      },
    },
  },
  plugins: [],
};
