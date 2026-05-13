import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import ruRU from 'antd/locale/ru_RU';
import App from './App.tsx';
import { ThemeProvider, useTheme } from './context/ThemeContext.tsx';
import './index.css';

const DARK_THEME = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#1677ff',
    colorBgBase: '#0d1117',
    colorBgContainer: '#161b22',
    colorBgElevated: '#1c2128',
    colorBgLayout: '#0d1117',
    colorBorder: '#30363d',
    colorBorderSecondary: '#21262d',
    colorText: '#e6edf3',
    colorTextSecondary: '#848d97',
    borderRadius: 8,
    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize: 14,
    lineHeight: 1.5714,
  },
  components: {
    Layout: { bodyBg: '#0d1117', headerBg: '#161b22', siderBg: '#0d1117' },
    Menu: {
      darkItemBg: '#0d1117',
      darkSubMenuItemBg: '#080c14',
      darkItemSelectedBg: 'rgba(22, 119, 255, 0.15)',
      darkItemSelectedColor: '#1677ff',
      darkItemHoverBg: 'rgba(22, 119, 255, 0.08)',
      darkItemHoverColor: '#e6edf3',
    },
    Table: { headerBg: '#1c2128', rowHoverBg: 'rgba(22, 119, 255, 0.05)', borderColor: '#21262d' },
    Card: { headerBg: '#1c2128' },
    Modal: { contentBg: '#161b22', headerBg: '#1c2128' },
  },
};

const LIGHT_THEME = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: '#1677ff',
    colorBgBase: '#ffffff',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#f6f8fa',
    colorBgLayout: '#f6f8fa',
    colorBorder: '#d0d7de',
    colorBorderSecondary: '#d8dee4',
    colorText: '#1f2328',
    colorTextSecondary: '#636c76',
    borderRadius: 8,
    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize: 14,
    lineHeight: 1.5714,
  },
  components: {
    Layout: { bodyBg: '#f6f8fa', headerBg: '#ffffff', siderBg: '#ffffff' },
    Table: { rowHoverBg: 'rgba(22, 119, 255, 0.04)' },
  },
};

function ThemedApp() {
  const { isDark } = useTheme();
  return (
    <ConfigProvider locale={ruRU} theme={isDark ? DARK_THEME : LIGHT_THEME}>
      <App />
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <ThemedApp />
    </ThemeProvider>
  </StrictMode>,
);
