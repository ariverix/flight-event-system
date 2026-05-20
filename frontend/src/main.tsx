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
    colorPrimary:           '#3b82f6',
    colorSuccess:           '#10b981',
    colorWarning:           '#f59e0b',
    colorError:             '#ef4444',
    colorInfo:              '#3b82f6',
    colorBgBase:            '#0a0f1e',
    colorBgContainer:       'rgba(13,21,48,0.85)',
    colorBgElevated:        'rgba(17,24,39,0.92)',
    colorBgLayout:          '#0a0f1e',
    colorBgSpotlight:       'rgba(17,24,39,0.96)',
    colorBorder:            'rgba(255,255,255,0.10)',
    colorBorderSecondary:   'rgba(255,255,255,0.06)',
    colorText:              'rgba(255,255,255,0.95)',
    colorTextSecondary:     'rgba(255,255,255,0.60)',
    colorTextTertiary:      'rgba(255,255,255,0.38)',
    colorTextQuaternary:    'rgba(255,255,255,0.22)',
    colorFill:              'rgba(255,255,255,0.08)',
    colorFillSecondary:     'rgba(255,255,255,0.05)',
    colorFillTertiary:      'rgba(255,255,255,0.03)',
    borderRadius:            12,
    borderRadiusLG:          16,
    borderRadiusSM:          8,
    fontFamily:             "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize:                14,
    lineHeight:              1.5714,
    boxShadow:              '0 1px 0 rgba(255,255,255,0.08) inset, 0 16px 40px rgba(0,0,0,0.3)',
    boxShadowSecondary:     '0 8px 24px rgba(0,0,0,0.4)',
  },
  components: {
    Layout: {
      bodyBg:   '#0a0f1e',
      headerBg: 'rgba(10,15,30,0.85)',
      siderBg:  'rgba(10,15,30,0.90)',
    },
    Menu: {
      darkItemBg:          'transparent',
      darkSubMenuItemBg:   'transparent',
      darkItemSelectedBg:  'rgba(59,130,246,0.15)',
      darkItemSelectedColor: '#60a5fa',
      darkItemHoverBg:     'rgba(59,130,246,0.08)',
      darkItemHoverColor:  'rgba(255,255,255,0.95)',
      darkItemColor:       'rgba(255,255,255,0.60)',
      itemBorderRadius:    10,
      itemMarginInline:    8,
    },
    Table: {
      colorBgContainer:   'transparent',
      headerBg:           'rgba(255,255,255,0.03)',
      rowHoverBg:         'rgba(59,130,246,0.06)',
      borderColor:        'rgba(255,255,255,0.06)',
      headerColor:        'rgba(255,255,255,0.55)',
      cellPaddingBlock:   10,
    },
    Card: {
      colorBgContainer:   'rgba(13,21,48,0.75)',
      colorBorderSecondary: 'rgba(255,255,255,0.10)',
      headerBg:           'rgba(255,255,255,0.04)',
      borderRadiusLG:     16,
      paddingLG:          20,
    },
    Modal: {
      contentBg:  'rgba(13,21,48,0.90)',
      headerBg:   'rgba(255,255,255,0.04)',
      borderRadiusLG: 16,
    },
    Drawer: {
      colorBgElevated: 'rgba(13,21,48,0.95)',
    },
    Input: {
      colorBgContainer:  'rgba(255,255,255,0.04)',
      activeBorderColor: '#3b82f6',
      hoverBorderColor:  'rgba(59,130,246,0.5)',
      activeShadow:      '0 0 0 2px rgba(59,130,246,0.20)',
    },
    Select: {
      colorBgContainer:   'rgba(255,255,255,0.04)',
      colorBgElevated:    'rgba(13,21,48,0.95)',
      optionSelectedBg:   'rgba(59,130,246,0.15)',
      optionActiveBg:     'rgba(255,255,255,0.05)',
    },
    DatePicker: {
      colorBgContainer:  'rgba(255,255,255,0.04)',
      colorBgElevated:   'rgba(13,21,48,0.95)',
    },
    Dropdown: {
      colorBgElevated:   'rgba(13,21,48,0.95)',
    },
    Tooltip: {
      colorBgSpotlight:  'rgba(13,21,48,0.96)',
      colorTextLightSolid: 'rgba(255,255,255,0.9)',
    },
    Tag: {
      defaultBg:    'rgba(255,255,255,0.06)',
      defaultColor: 'rgba(255,255,255,0.70)',
    },
    Descriptions: {
      colorBgContainer: 'transparent',
      labelBg:          'rgba(255,255,255,0.04)',
    },
    Timeline: {
      colorText:          'rgba(255,255,255,0.85)',
      colorTextSecondary: 'rgba(255,255,255,0.55)',
      dotBg:              'rgba(13,21,48,0.85)',
    },
    Steps: {
      colorTextDescription: 'rgba(255,255,255,0.50)',
    },
    Collapse: {
      colorBgContainer:  'transparent',
      headerBg:          'rgba(255,255,255,0.03)',
      contentBg:         'transparent',
      colorBorder:       'rgba(255,255,255,0.08)',
    },
    Skeleton: {
      gradientFromColor: 'rgba(255,255,255,0.05)',
      gradientToColor:   'rgba(255,255,255,0.10)',
    },
    Progress: {
      remainingColor: 'rgba(255,255,255,0.08)',
    },
    Statistic: {
      contentFontSize: 28,
    },
    Badge: {
      colorBgContainer: 'rgba(13,21,48,0.9)',
    },
    Alert: {
      colorInfoBg:    'rgba(59,130,246,0.12)',
      colorInfoBorder:'rgba(59,130,246,0.3)',
      colorSuccessBg: 'rgba(16,185,129,0.12)',
      colorWarningBg: 'rgba(245,158,11,0.12)',
      colorErrorBg:   'rgba(239,68,68,0.12)',
    },
    List: {
      colorSplit: 'rgba(255,255,255,0.06)',
    },
    Divider: {
      colorSplit: 'rgba(255,255,255,0.08)',
    },
    AutoComplete: {
      colorBgContainer:  'rgba(255,255,255,0.04)',
      colorBgElevated:   'rgba(13,21,48,0.95)',
    },
  },
};

const LIGHT_THEME = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary:         '#3b82f6',
    colorSuccess:         '#10b981',
    colorWarning:         '#f59e0b',
    colorError:           '#ef4444',
    colorBgBase:          '#ffffff',
    colorBgContainer:     '#ffffff',
    colorBgElevated:      '#f6f8fa',
    colorBgLayout:        '#eef2ff',
    colorBorder:          'rgba(0,0,0,0.10)',
    colorBorderSecondary: 'rgba(0,0,0,0.06)',
    colorText:            '#1f2328',
    colorTextSecondary:   '#636c76',
    borderRadius:          12,
    borderRadiusLG:        16,
    borderRadiusSM:        8,
    fontFamily:           "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize:              14,
  },
  components: {
    Layout: { bodyBg: '#eef2ff', headerBg: 'rgba(255,255,255,0.85)', siderBg: 'rgba(255,255,255,0.85)' },
    Menu: {
      itemSelectedBg:    'rgba(59,130,246,0.10)',
      itemSelectedColor: '#2563eb',
      itemHoverBg:       'rgba(59,130,246,0.06)',
      itemBorderRadius:  10,
      itemMarginInline:  8,
    },
    Table: {
      headerBg:    'rgba(0,0,0,0.02)',
      rowHoverBg:  'rgba(59,130,246,0.04)',
      borderColor: 'rgba(0,0,0,0.06)',
      cellPaddingBlock: 10,
    },
    Card: {
      colorBgContainer: 'rgba(255,255,255,0.80)',
      borderRadiusLG: 16,
      paddingLG: 20,
    },
    Statistic: { contentFontSize: 28 },
    Progress: { remainingColor: 'rgba(0,0,0,0.06)' },
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
