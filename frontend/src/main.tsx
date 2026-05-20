import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider, theme as antTheme } from 'antd';
import ruRU from 'antd/locale/ru_RU';
import App from './App.tsx';
import { ThemeProvider, useTheme } from './context/ThemeContext.tsx';
import './index.css';

const DARK_THEME = {
  algorithm: antTheme.darkAlgorithm,
  token: {
    colorBgBase:            '#04050d',
    colorBgContainer:       'rgba(255,255,255,0.04)',
    colorBgElevated:        'rgba(13,18,37,0.96)',
    colorBgLayout:          '#04050d',
    colorBorder:            'rgba(255,255,255,0.08)',
    colorBorderSecondary:   'rgba(255,255,255,0.05)',
    colorPrimary:           '#3b82f6',
    colorSuccess:           '#10b981',
    colorWarning:           '#f59e0b',
    colorError:             '#ef4444',
    colorInfo:              '#3b82f6',
    colorText:              'rgba(255,255,255,0.90)',
    colorTextSecondary:     'rgba(255,255,255,0.60)',
    colorTextTertiary:      'rgba(255,255,255,0.38)',
    colorTextQuaternary:    'rgba(255,255,255,0.20)',
    colorFill:              'rgba(255,255,255,0.08)',
    colorFillSecondary:     'rgba(255,255,255,0.05)',
    colorFillTertiary:      'rgba(255,255,255,0.03)',
    borderRadius:            10,
    borderRadiusLG:          14,
    borderRadiusSM:          7,
    fontFamily:             "-apple-system,'SF Pro Display','Inter',system-ui,sans-serif",
    fontSize:                14,
    lineHeight:              1.6,
    boxShadow:              '0 8px 32px rgba(0,0,0,0.45)',
    controlHeight:           38,
    controlHeightLG:         46,
  },
  components: {
    Layout: {
      siderBg:   'rgba(4,5,13,0.88)',
      headerBg:  'rgba(4,5,13,0.72)',
      bodyBg:    'transparent',
      triggerBg: 'rgba(255,255,255,0.04)',
    },
    Menu: {
      darkItemBg:            'transparent',
      darkSubMenuItemBg:     'transparent',
      darkItemSelectedBg:    'rgba(59,130,246,0.16)',
      darkItemSelectedColor: '#3b82f6',
      darkItemHoverBg:       'rgba(255,255,255,0.05)',
      darkItemHoverColor:    'rgba(255,255,255,0.92)',
      darkItemColor:         'rgba(255,255,255,0.58)',
      itemBorderRadius:      10,
      itemMarginInline:      8,
    },
    Card: {
      colorBgContainer:     'rgba(255,255,255,0.04)',
      colorBorderSecondary: 'rgba(255,255,255,0.08)',
      headerBg:             'rgba(255,255,255,0.03)',
      borderRadiusLG:        16,
      paddingLG:             22,
    },
    Table: {
      colorBgContainer:     'transparent',
      headerBg:             'rgba(255,255,255,0.04)',
      headerColor:          'rgba(255,255,255,0.42)',
      borderColor:          'rgba(255,255,255,0.06)',
      rowHoverBg:           'rgba(59,130,246,0.05)',
      colorText:            'rgba(255,255,255,0.82)',
      cellPaddingBlock:      13,
      headerSortActiveBg:   'rgba(255,255,255,0.05)',
      headerSortHoverBg:    'rgba(255,255,255,0.05)',
    },
    Button: {
      defaultBg:              'rgba(255,255,255,0.06)',
      defaultBorderColor:     'rgba(255,255,255,0.12)',
      defaultColor:           'rgba(255,255,255,0.82)',
      defaultHoverBg:         'rgba(255,255,255,0.10)',
      defaultHoverBorderColor:'rgba(255,255,255,0.22)',
      primaryShadow:          '0 4px 16px rgba(59,130,246,0.38)',
      borderRadius:            10,
      borderRadiusLG:          12,
    },
    Input: {
      colorBgContainer:     'rgba(255,255,255,0.04)',
      colorBorder:          'rgba(255,255,255,0.10)',
      activeBorderColor:    'rgba(59,130,246,0.65)',
      hoverBorderColor:     'rgba(255,255,255,0.22)',
      colorTextPlaceholder: 'rgba(255,255,255,0.28)',
      activeShadow:         '0 0 0 3px rgba(59,130,246,0.14)',
    },
    Select: {
      colorBgContainer:    'rgba(255,255,255,0.04)',
      colorBorder:         'rgba(255,255,255,0.10)',
      colorBgElevated:     'rgba(8,11,24,0.98)',
      optionSelectedBg:    'rgba(59,130,246,0.15)',
      optionActiveBg:      'rgba(255,255,255,0.05)',
    },
    Modal: {
      contentBg:       'rgba(8,11,24,0.96)',
      headerBg:        'transparent',
      footerBg:        'transparent',
      borderRadiusLG:   20,
    },
    Drawer:     { colorBgElevated: 'rgba(8,11,24,0.98)' },
    Tag:        { defaultBg: 'rgba(255,255,255,0.06)', defaultColor: 'rgba(255,255,255,0.72)' },
    Badge:      { colorBgContainer: 'rgba(4,5,13,0.88)' },
    Statistic:  { colorTextHeading: 'rgba(255,255,255,0.52)', contentFontSize: 28 },
    Progress:   { defaultColor: '#10b981', colorSuccess: '#10b981', remainingColor: 'rgba(255,255,255,0.07)' },
    Switch:     { colorPrimary: '#10b981', colorPrimaryHover: '#059669' },
    Timeline:   { colorText: 'rgba(255,255,255,0.72)' },
    Pagination: {
      colorBgContainer: 'rgba(255,255,255,0.04)',
      colorBorder:      'rgba(255,255,255,0.08)',
      itemActiveBg:     'rgba(59,130,246,0.18)',
    },
    Tooltip:      { colorBgSpotlight: 'rgba(8,11,24,0.96)', borderRadius: 10 },
    Message:      { contentBg: 'rgba(8,11,24,0.96)', colorText: 'rgba(255,255,255,0.90)' },
    Notification: { colorBgElevated: 'rgba(8,11,24,0.96)', borderRadiusLG: 14 },
    Skeleton:     { color: 'rgba(255,255,255,0.06)', colorGradientEnd: 'rgba(255,255,255,0.11)' },
    Empty:        { colorTextDescription: 'rgba(255,255,255,0.30)' },
    DatePicker: {
      colorBgContainer: 'rgba(255,255,255,0.04)',
      colorBorder:      'rgba(255,255,255,0.10)',
      colorBgElevated:  'rgba(8,11,24,0.98)',
    },
    Collapse: {
      colorBgContainer: 'transparent',
      headerBg:         'rgba(255,255,255,0.03)',
      contentBg:        'transparent',
      colorBorder:      'rgba(255,255,255,0.07)',
    },
    Descriptions: {
      colorBgContainer: 'transparent',
      labelBg:          'rgba(255,255,255,0.03)',
    },
    AutoComplete: {
      colorBgContainer: 'rgba(255,255,255,0.04)',
      colorBgElevated:  'rgba(8,11,24,0.98)',
    },
    Alert: {
      colorInfoBg:    'rgba(59,130,246,0.10)',
      colorInfoBorder:'rgba(59,130,246,0.28)',
      colorSuccessBg: 'rgba(16,185,129,0.10)',
      colorWarningBg: 'rgba(245,158,11,0.10)',
      colorErrorBg:   'rgba(239,68,68,0.10)',
    },
    List:    { colorSplit: 'rgba(255,255,255,0.06)' },
    Divider: { colorSplit: 'rgba(255,255,255,0.07)' },
    Tabs: {
      inkBarColor:       '#3b82f6',
      itemSelectedColor: '#3b82f6',
      itemHoverColor:    'rgba(255,255,255,0.85)',
      cardBg:            'rgba(255,255,255,0.04)',
    },
  },
};

const LIGHT_THEME = {
  algorithm: antTheme.defaultAlgorithm,
  token: {
    colorPrimary:           '#3b82f6',
    colorSuccess:           '#10b981',
    colorWarning:           '#f59e0b',
    colorError:             '#ef4444',
    colorBgBase:            '#ffffff',
    colorBgContainer:       '#ffffff',
    colorBgElevated:        '#f6f8fa',
    colorBgLayout:          '#eef2ff',
    colorBorder:            'rgba(0,0,0,0.10)',
    colorBorderSecondary:   'rgba(0,0,0,0.06)',
    colorText:              '#1f2328',
    colorTextSecondary:     '#636c76',
    borderRadius:            10,
    borderRadiusLG:          14,
    borderRadiusSM:          7,
    fontFamily:             "-apple-system,'SF Pro Display','Inter',system-ui,sans-serif",
    fontSize:                14,
    controlHeight:           38,
  },
  components: {
    Layout: { bodyBg: '#eef2ff', headerBg: 'rgba(255,255,255,0.82)', siderBg: 'rgba(255,255,255,0.85)' },
    Menu: {
      itemSelectedBg:    'rgba(59,130,246,0.10)',
      itemSelectedColor: '#2563eb',
      itemHoverBg:       'rgba(59,130,246,0.06)',
      itemBorderRadius:  10, itemMarginInline: 8,
    },
    Table: {
      headerBg: 'rgba(0,0,0,0.02)', rowHoverBg: 'rgba(59,130,246,0.04)',
      borderColor: 'rgba(0,0,0,0.06)', cellPaddingBlock: 13,
    },
    Card:      { colorBgContainer: 'rgba(255,255,255,0.80)', borderRadiusLG: 16, paddingLG: 22 },
    Statistic: { contentFontSize: 28 },
    Progress:  { remainingColor: 'rgba(0,0,0,0.06)' },
    Switch:    { colorPrimary: '#10b981' },
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
