import React from 'react';

export interface PanelBtnProps {
  icon: React.ReactNode;
  title: string;
  onClick: () => void;
  isDark: boolean;
}

/** Плоская иконка-кнопка тулбара React Flow (fit view / auto-layout / fullscreen и т.п.). */
export const PanelBtn: React.FC<PanelBtnProps> = ({ icon, title, onClick, isDark }) => (
  <button
    title={title}
    onClick={onClick}
    className="panel-btn-press"
    style={{
      background: 'transparent',
      border: 'none',
      color: isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)',
      width: 30,
      height: 30,
      borderRadius: 8,
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: 14,
      transition: 'background 0.15s ease, color 0.15s ease, transform 0.12s var(--ease-out)',
      padding: 0,
    }}
    onMouseEnter={e => {
      const el = e.currentTarget as HTMLButtonElement;
      el.style.background = isDark ? 'rgba(255,255,255,0.09)' : 'rgba(0,0,0,0.07)';
      el.style.color = isDark ? 'rgba(255,255,255,0.90)' : 'rgba(0,0,0,0.80)';
    }}
    onMouseLeave={e => {
      const el = e.currentTarget as HTMLButtonElement;
      el.style.background = 'transparent';
      el.style.color = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)';
    }}
  >
    {icon}
  </button>
);
