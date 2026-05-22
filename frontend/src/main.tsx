import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider } from 'antd';
import ruRU from 'antd/locale/ru_RU';
import App from './App.tsx';
import { ThemeProvider, useTheme } from './context/ThemeContext.tsx';
import { DARK_THEME, LIGHT_THEME } from './theme/darkTheme.ts';
import './index.css';

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
