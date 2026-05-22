import { theme as antTheme } from 'antd';

export const DARK_THEME = {
  algorithm: antTheme.darkAlgorithm,

  token: {
    // ── ФОНЫ ─────────────────────────────────────────
    colorBgBase:            '#040508',
    colorBgContainer:       'rgba(255,255,255,0.042)',
    colorBgElevated:        'rgba(8,10,22,0.97)',
    colorBgLayout:          '#040508',
    colorBgSpotlight:       'rgba(8,10,22,0.96)',
    colorBgMask:            'rgba(0,0,0,0.60)',

    // ── ГРАНИЦЫ ──────────────────────────────────────
    colorBorder:            'rgba(255,255,255,0.09)',
    colorBorderSecondary:   'rgba(255,255,255,0.055)',
    colorSplit:             'rgba(255,255,255,0.07)',

    // ── ЗАЛИВКИ ──────────────────────────────────────
    colorFill:              'rgba(255,255,255,0.08)',
    colorFillSecondary:     'rgba(255,255,255,0.05)',
    colorFillTertiary:      'rgba(255,255,255,0.03)',

    // ── АКЦЕНТЫ ──────────────────────────────────────
    colorPrimary:           '#3b82f6',
    colorSuccess:           '#10b981',
    borderRadius:            10,
    colorWarning:           '#f59e0b',
    colorError:             '#ef4444',
    colorInfo:              '#06b6d4',

    // ── ТЕКСТ ────────────────────────────────────────
    colorText:              'rgba(255,255,255,0.88)',
    colorTextSecondary:     'rgba(255,255,255,0.55)',
    colorTextTertiary:      'rgba(255,255,255,0.35)',
    colorTextQuaternary:    'rgba(255,255,255,0.20)',
    colorTextPlaceholder:   'rgba(255,255,255,0.26)',
    colorTextDisabled:      'rgba(255,255,255,0.22)',
    colorTextHeading:       'rgba(255,255,255,0.92)',

    // ── РАДИУСЫ ──────────────────────────────────────
    borderRadiusLG:          14,
    borderRadiusSM:          7,
    borderRadiusXS:          5,

    // ── ШРИФТ ────────────────────────────────────────
    fontFamily:             "-apple-system,'SF Pro Display','Inter',system-ui,sans-serif",
    fontSize:                14,
    fontSizeLG:              16,
    fontSizeSM:              12,
    lineHeight:              1.6,

    // ── КОНТРОЛЫ ─────────────────────────────────────
    controlHeight:           38,
    controlHeightLG:         46,
    controlHeightSM:         30,
    paddingContentHorizontal:16,

    // ── ТЕНИ ─────────────────────────────────────────
    boxShadow:              '0 8px 32px rgba(0,0,0,0.45), 0 2px 8px rgba(0,0,0,0.25)',
    boxShadowSecondary:     '0 4px 16px rgba(0,0,0,0.28)',
  },

  components: {

    Layout: {
      siderBg:   'rgba(4,5,8,0.92)',
      headerBg:  'rgba(4,5,8,0.78)',
      bodyBg:    'transparent',
      triggerBg: 'rgba(255,255,255,0.04)',
    },

    Menu: {
      darkItemBg:            'transparent',
      darkSubMenuItemBg:     'transparent',
      darkItemSelectedBg:    'rgba(59,130,246,0.15)',
      darkItemSelectedColor: '#3b82f6',
      darkItemHoverBg:       'rgba(255,255,255,0.055)',
      darkItemHoverColor:    'rgba(255,255,255,0.92)',
      darkItemColor:         'rgba(255,255,255,0.58)',
      itemBorderRadius:      10,
      itemMarginInline:      8,
      itemPaddingInline:     14,
    },

    Card: {
      colorBgContainer:     'rgba(255,255,255,0.042)',
      colorBorderSecondary: 'rgba(255,255,255,0.09)',
      headerBg:             'transparent',
      borderRadiusLG:        16,
      paddingLG:             24,
    },

    Table: {
      colorBgContainer:     'transparent',
      headerBg:             'rgba(255,255,255,0.042)',
      headerColor:          'rgba(255,255,255,0.44)',
      headerSortActiveBg:   'rgba(255,255,255,0.065)',
      headerSortHoverBg:    'rgba(255,255,255,0.065)',
      headerSplitColor:     'rgba(255,255,255,0.065)',
      borderColor:          'rgba(255,255,255,0.065)',
      rowHoverBg:           'rgba(59,130,246,0.055)',
      rowSelectedBg:        'rgba(59,130,246,0.11)',
      rowSelectedHoverBg:   'rgba(59,130,246,0.16)',
      colorText:            'rgba(255,255,255,0.82)',
      filterDropdownBg:     'rgba(8,10,22,0.98)',
      cellPaddingBlock:      13,
    },

    Button: {
      defaultBg:              'rgba(255,255,255,0.065)',
      defaultBorderColor:     'rgba(255,255,255,0.13)',
      defaultColor:           'rgba(255,255,255,0.82)',
      defaultHoverBg:         'rgba(255,255,255,0.10)',
      defaultHoverBorderColor:'rgba(255,255,255,0.22)',
      defaultHoverColor:      'rgba(255,255,255,0.95)',
      defaultActiveBg:        'rgba(255,255,255,0.08)',
      primaryShadow:          '0 4px 14px rgba(59,130,246,0.38)',
      dangerShadow:           '0 4px 14px rgba(239,68,68,0.28)',
      borderRadius:            10,
      borderRadiusLG:          12,
      borderRadiusSM:          8,
    },

    Input: {
      colorBgContainer:     'rgba(255,255,255,0.042)',
      colorBorder:          'rgba(255,255,255,0.11)',
      activeBorderColor:    'rgba(59,130,246,0.58)',
      hoverBorderColor:     'rgba(255,255,255,0.22)',
      colorTextPlaceholder: 'rgba(255,255,255,0.26)',
      activeShadow:         '0 0 0 3px rgba(59,130,246,0.13)',
      errorActiveShadow:    '0 0 0 3px rgba(239,68,68,0.13)',
      addonBg:              'rgba(255,255,255,0.042)',
    },

    Select: {
      colorBgContainer:    'rgba(255,255,255,0.042)',
      colorBorder:         'rgba(255,255,255,0.11)',
      colorBgElevated:     'rgba(8,10,22,0.98)',
      optionSelectedBg:    'rgba(59,130,246,0.15)',
      optionActiveBg:      'rgba(255,255,255,0.055)',
      selectorBg:          'rgba(255,255,255,0.042)',
    },

    Modal: {
      contentBg:                  'rgba(8,10,22,0.97)',
      headerBg:                   'transparent',
      footerBg:                   'transparent',
      colorText:                  'rgba(255,255,255,0.88)',
      borderRadiusLG:              20,
      paddingContentHorizontalLG:  28,
    },

    Drawer: {
      colorBgElevated: 'rgba(8,10,22,0.98)',
    },

    Dropdown: {
      colorBgElevated:       'rgba(8,10,22,0.98)',
      controlItemBgHover:    'rgba(255,255,255,0.055)',
      controlItemBgActive:   'rgba(59,130,246,0.13)',
    },

    Tag: {
      defaultBg:    'rgba(255,255,255,0.07)',
      defaultColor: 'rgba(255,255,255,0.72)',
      borderRadius:  8,
    },

    Badge: {
      colorBgContainer: 'rgba(4,5,8,0.92)',
    },

    Tooltip: {
      colorBgSpotlight: 'rgba(6,8,20,0.96)',
      colorText:        'rgba(255,255,255,0.88)',
      borderRadius:      10,
    },

    Pagination: {
      colorBgContainer: 'rgba(255,255,255,0.042)',
      colorBorder:      'rgba(255,255,255,0.09)',
      itemActiveBg:     'rgba(59,130,246,0.22)',
      itemSize:          32,
    },

    Tabs: {
      inkBarColor:       '#3b82f6',
      itemSelectedColor: '#3b82f6',
      itemHoverColor:    'rgba(255,255,255,0.85)',
      itemColor:         'rgba(255,255,255,0.52)',
      cardBg:            'rgba(255,255,255,0.042)',
    },

    Progress: {
      defaultColor:    '#10b981',
      colorSuccess:    '#10b981',
      remainingColor:  'rgba(255,255,255,0.07)',
    },

    Switch: {
      colorPrimary:      '#10b981',
      colorPrimaryHover: '#059669',
      handleBg:          '#ffffff',
    },

    Skeleton: {
      color:            'rgba(255,255,255,0.065)',
      colorGradientEnd: 'rgba(255,255,255,0.11)',
    },

    Empty: {
      colorTextDescription: 'rgba(255,255,255,0.30)',
    },

    DatePicker: {
      colorBgContainer:      'rgba(255,255,255,0.042)',
      colorBorder:           'rgba(255,255,255,0.11)',
      colorBgElevated:       'rgba(8,10,22,0.98)',
      cellHoverBg:           'rgba(255,255,255,0.055)',
      cellActiveWithRangeBg: 'rgba(59,130,246,0.11)',
    },

    Form: {
      labelColor:             'rgba(255,255,255,0.68)',
      labelRequiredMarkColor: '#ef4444',
      itemMarginBottom:        20,
    },

    Message: {
      contentBg: 'rgba(8,10,22,0.97)',
      colorText:  'rgba(255,255,255,0.90)',
    },

    Notification: {
      colorBgElevated: 'rgba(8,10,22,0.97)',
      borderRadiusLG:   14,
      colorText:       'rgba(255,255,255,0.88)',
    },

    Timeline: {
      colorText: 'rgba(255,255,255,0.72)',
      dotBg:     'rgba(4,5,8,0.92)',
    },

    Statistic: {
      colorTextHeading:     'rgba(255,255,255,0.48)',
      colorTextDescription: 'rgba(255,255,255,0.34)',
      contentFontSize:       36,
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
      colorBgContainer: 'rgba(255,255,255,0.042)',
      colorBgElevated:  'rgba(8,10,22,0.98)',
    },

    Alert: {
      colorInfoBg:     'rgba(59,130,246,0.10)',
      colorInfoBorder: 'rgba(59,130,246,0.28)',
      colorSuccessBg:  'rgba(16,185,129,0.10)',
      colorWarningBg:  'rgba(245,158,11,0.10)',
      colorErrorBg:    'rgba(239,68,68,0.10)',
    },

    List:    { colorSplit: 'rgba(255,255,255,0.06)' },
    Divider: { colorSplit: 'rgba(255,255,255,0.07)' },
  },
};

export const LIGHT_THEME = {
  algorithm: antTheme.defaultAlgorithm,
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
    borderRadius:          10,
    borderRadiusLG:        14,
    borderRadiusSM:        7,
    fontFamily:           "-apple-system,'SF Pro Display','Inter',system-ui,sans-serif",
    fontSize:              14,
    controlHeight:         38,
  },
  components: {
    Layout: { bodyBg: '#eef2ff', headerBg: 'rgba(255,255,255,0.82)', siderBg: 'rgba(255,255,255,0.85)' },
    Menu: {
      itemSelectedBg:    'rgba(59,130,246,0.10)',
      itemSelectedColor: '#2563eb',
      itemHoverBg:       'rgba(59,130,246,0.06)',
      itemBorderRadius:   10,
      itemMarginInline:   8,
    },
    Table: {
      headerBg:      'rgba(0,0,0,0.02)',
      rowHoverBg:    'rgba(59,130,246,0.04)',
      borderColor:   'rgba(0,0,0,0.06)',
      cellPaddingBlock: 13,
    },
    Card:      { colorBgContainer: 'rgba(255,255,255,0.80)', borderRadiusLG: 16, paddingLG: 22 },
    Statistic: { contentFontSize: 36 },
    Progress:  { remainingColor: 'rgba(0,0,0,0.06)' },
    Switch:    { colorPrimary: '#10b981' },
    Divider:   { colorSplit: 'rgba(0,0,0,0.08)' },
  },
};
