import React, { memo, useState } from 'react';
import { Tag } from 'antd';
import {
  MessageOutlined, PlayCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined,
  ThunderboltOutlined, RightOutlined,
} from '@ant-design/icons';
import { useTheme } from '../../context/ThemeContext';
import type { TLEventType } from '../../hooks/useTimeline';

// Цветовая конфигурация по типу события — системные цвета macOS через CSS
// custom properties (var(--accent-*)), поэтому не зависит от темы в JS:
// браузер подставляет тёмный/светлый оттенок акцента сам.
const EVENT_CFG: Record<TLEventType, { icon: React.ReactNode; color: string; bg: string; bd: string; label: string }> = {
  MESSAGE_RECEIVED:    { icon:<MessageOutlined />,     color:'var(--accent-blue)',   bg:'rgba(var(--accent-blue-rgb), 0.10)',   bd:'rgba(var(--accent-blue-rgb), 0.28)',   label:'Сообщение'  },
  EXECUTION_STARTED:   { icon:<PlayCircleOutlined />,  color:'var(--accent-green)',  bg:'rgba(var(--accent-green-rgb), 0.10)',  bd:'rgba(var(--accent-green-rgb), 0.28)',  label:'Запуск'     },
  STEP_COMPLETED:      { icon:<ThunderboltOutlined />, color:'var(--accent-purple)', bg:'rgba(var(--accent-purple-rgb), 0.10)', bd:'rgba(var(--accent-purple-rgb), 0.28)', label:'Шаг'        },
  EXECUTION_COMPLETED: { icon:<CheckCircleOutlined />, color:'var(--accent-green)',  bg:'rgba(var(--accent-green-rgb), 0.12)',  bd:'rgba(var(--accent-green-rgb), 0.32)',  label:'Завершено'  },
  EXECUTION_FAILED:    { icon:<CloseCircleOutlined />, color:'var(--accent-red)',    bg:'rgba(var(--accent-red-rgb), 0.12)',    bd:'rgba(var(--accent-red-rgb), 0.32)',    label:'Ошибка'     },
};

const fmtTime = (iso: string) => {
  try {
    return new Date(iso).toLocaleTimeString('ru-RU', { hour:'2-digit', minute:'2-digit', second:'2-digit', timeZone: 'Europe/Moscow' });
  } catch { return '??:??'; }
};

const DIR_LABEL: Record<string, { text: string; color: string; bg: string; bd: string }> = {
  GROUND:   { text: 'Наземная',   color: 'var(--accent-amber)',  bg: 'rgba(var(--accent-amber-rgb), 0.12)',  bd: 'rgba(var(--accent-amber-rgb), 0.25)' },
  DOWNLINK: { text: 'Нисходящая', color: 'var(--accent-blue)',   bg: 'rgba(var(--accent-blue-rgb), 0.12)',   bd: 'rgba(var(--accent-blue-rgb), 0.25)' },
  UPLINK:   { text: 'Восходящая', color: 'var(--accent-purple)', bg: 'rgba(var(--accent-purple-rgb), 0.12)', bd: 'rgba(var(--accent-purple-rgb), 0.25)' },
};

const STEP_TYPE_CFG: Record<string, { label: string; color: string; bg: string; bd: string }> = {
  ACTION:   { label: '⚡ ACTION', color: 'var(--accent-blue)',   bg: 'rgba(var(--accent-blue-rgb), 0.10)',   bd: 'rgba(var(--accent-blue-rgb), 0.28)' },
  WAIT:     { label: '⏳ WAIT',   color: 'var(--accent-amber)',  bg: 'rgba(var(--accent-amber-rgb), 0.10)',  bd: 'rgba(var(--accent-amber-rgb), 0.28)' },
  EVALUATE: { label: '🔍 EVAL',  color: 'var(--accent-purple)', bg: 'rgba(var(--accent-purple-rgb), 0.10)', bd: 'rgba(var(--accent-purple-rgb), 0.28)' },
};

interface EventBodyProps { event: any; textMain: string; textMuted: string; isDark: boolean }

const EventBody: React.FC<EventBodyProps> = ({ event, textMain, textMuted, isDark }) => {
  switch (event.type as TLEventType) {
    case 'MESSAGE_RECEIVED':
      return (
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: textMain, fontFamily: 'monospace', marginBottom: 5 }}>
            {event.msgTemplate || 'Неизвестный шаблон'}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            {event.msgDirection && (() => {
              const d = DIR_LABEL[event.msgDirection.toUpperCase()]
                ?? { text: event.msgDirection, color: '#8e8e93', bg: 'rgba(142,142,147,0.12)', bd: 'rgba(142,142,147,0.25)' };
              return (
                <Tag style={{ background: d.bg, border: `1px solid ${d.bd}`, color: d.color, borderRadius: 6, fontSize: 11, fontWeight: 600, padding: '1px 8px' }}>
                  {d.text}
                </Tag>
              );
            })()}
            {event.aircraftId && (
              <span style={{ fontSize: 12, color: textMuted, fontFamily: 'monospace' }}>
                ✈ {event.aircraftId}{event.flightNumber ? ` · ${event.flightNumber}` : ''}
              </span>
            )}
          </div>
        </div>
      );

    case 'EXECUTION_STARTED':
      return (
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: textMain, marginBottom: 5 }}>
            {event.seqName || `Выполнение #${event.execId}`}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Tag style={{ background:'rgba(var(--accent-green-rgb), 0.10)', border:'1px solid rgba(var(--accent-green-rgb), 0.28)', color:'var(--accent-green)', borderRadius:6, fontSize:11, fontWeight:600 }}>
              Запуск последовательности
            </Tag>
            <span style={{ fontSize: 11, color: textMuted, fontFamily: 'monospace' }}>#{event.execId}</span>
          </div>
        </div>
      );

    case 'STEP_COMPLETED': {
      const stCfg = STEP_TYPE_CFG[event.stepType] ?? STEP_TYPE_CFG.ACTION;
      return (
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: textMain, marginBottom: 5 }}>
            {event.stepLabel || `Шаг ${event.stepNum}`}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <Tag style={{
              background: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.05)',
              border: `1px solid ${isDark ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.12)'}`,
              color: textMuted, borderRadius:6, fontSize:11,
            }}>
              Шаг {event.stepNum}
            </Tag>
            {event.stepType && (
              <Tag style={{ background: stCfg.bg, border:`1px solid ${stCfg.bd}`, color: stCfg.color, borderRadius:6, fontSize:11, fontWeight:600 }}>
                {stCfg.label}
              </Tag>
            )}
            <Tag style={{
              background: event.stepResult === 'SUCCESS' ? 'rgba(var(--accent-green-rgb), 0.10)' : 'rgba(var(--accent-red-rgb), 0.10)',
              border: event.stepResult === 'SUCCESS' ? '1px solid rgba(var(--accent-green-rgb), 0.28)' : '1px solid rgba(var(--accent-red-rgb), 0.28)',
              color: event.stepResult === 'SUCCESS' ? 'var(--accent-green)' : 'var(--accent-red)',
              borderRadius: 6, fontSize: 11, fontWeight: 600,
            }}>
              {event.stepResult === 'SUCCESS' ? '✓ Успех' : '✗ Ошибка'}
            </Tag>
            {event.seqName && <span style={{ fontSize: 11, color: textMuted }}>{event.seqName}</span>}
          </div>
        </div>
      );
    }

    case 'EXECUTION_COMPLETED':
      return (
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: textMain, marginBottom: 4 }}>
            ✓ {event.seqName || `Выполнение #${event.execId}`}
          </div>
          <span style={{ fontSize: 12, color: 'var(--accent-green)', fontWeight: 500 }}>Последовательность успешно завершена</span>
        </div>
      );

    case 'EXECUTION_FAILED':
      return (
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: textMain, marginBottom: 4 }}>
            ✗ {event.seqName || `Выполнение #${event.execId}`}
          </div>
          <span style={{ fontSize: 12, color: 'var(--accent-red)', fontWeight: 500 }}>Выполнение завершено с ошибкой</span>
        </div>
      );

    default:
      return <div style={{ fontSize: 13, color: textMuted }}>Событие: {(event as any).type}</div>;
  }
};

interface TLEventCardProps {
  event: any;
  isNew?: boolean;
  showConnector?: boolean;
}

export const TLEventCard = memo(({ event, isNew = false, showConnector = true }: TLEventCardProps) => {
  const { isDark } = useTheme();
  const [expanded, setExpanded] = useState(false);

  const textMain   = isDark ? 'rgba(255,255,255,0.88)' : '#1d1d1f';
  const textMuted  = isDark ? 'rgba(255,255,255,0.48)' : '#6e6e73';
  const textDim    = isDark ? 'rgba(255,255,255,0.30)' : '#8e8e93';
  const cardBg     = isDark ? 'rgba(255,255,255,0.028)' : 'rgba(0,0,0,0.022)';
  const cardBorder = isDark ? 'rgba(255,255,255,0.065)' : 'rgba(0,0,0,0.08)';
  const connLine   = isDark ? 'rgba(255,255,255,0.06)'  : 'rgba(0,0,0,0.06)';
  const detailsBg  = isDark ? 'rgba(255,255,255,0.04)'  : 'rgba(0,0,0,0.04)';

  const cfg = EVENT_CFG[event.type as TLEventType]
    ?? { icon:<ThunderboltOutlined />, color:'#8e8e93', bg:'rgba(142,142,147,0.10)', bd:'rgba(142,142,147,0.28)', label: event.type };

  return (
    <div style={{
      display: 'flex', gap: 12, paddingBottom: 16,
      animation: isNew ? 'fadeInUp 0.35s ease both' : 'none',
      position: 'relative',
    }}>
      {showConnector && (
        <div style={{
          position: 'absolute', left: 17, top: 38, bottom: 0, width: 2,
          background: `linear-gradient(to bottom, ${cfg.bd}, ${connLine})`,
          borderRadius: 1, pointerEvents: 'none',
        }} />
      )}

      {/* Иконка-маркер */}
      <div style={{
        width: 36, height: 36, borderRadius: '50%',
        background: cfg.bg, border: `2px solid ${cfg.bd}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: cfg.color, fontSize: 16, flexShrink: 0, zIndex: 1,
        boxShadow: isNew ? `0 0 14px ${cfg.bg}` : 'none',
        transition: 'box-shadow 0.3s ease',
      }}>
        {cfg.icon}
      </div>

      {/* Карточка */}
      <div
        style={{
          flex: 1, minWidth: 0,
          background: isNew ? cfg.bg : cardBg,
          border: `1px solid ${isNew ? cfg.bd : cardBorder}`,
          borderRadius: 12, padding: '12px 16px',
          cursor: 'pointer',
          transition: 'all 0.25s ease',
        }}
        onClick={() => setExpanded(e => !e)}
      >
        {/* Шапка */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
          <Tag style={{
            background: cfg.bg, border: `1px solid ${cfg.bd}`, color: cfg.color,
            borderRadius: 6, fontSize: 10, fontWeight: 700,
            letterSpacing: '0.06em', textTransform: 'uppercase', padding: '1px 8px',
          }}>
            {cfg.label}
          </Tag>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 11, color: textDim, fontFamily: 'monospace' }}>
              {fmtTime(event.timestamp)}
            </span>
            <RightOutlined style={{
              fontSize: 10, color: textDim,
              transform: expanded ? 'rotate(90deg)' : 'none',
              transition: 'transform 0.2s ease',
            }} />
          </div>
        </div>

        {/* Основной контент — всегда видим */}
        <EventBody event={event} textMain={textMain} textMuted={textMuted} isDark={isDark} />

        {/* Детали при раскрытии */}
        {expanded && (
          <div style={{
            marginTop: 12, padding: '10px 12px',
            background: detailsBg, borderRadius: 8,
            animation: 'fadeIn 0.2s ease',
          }}>
            <div style={{ fontSize: 11, fontFamily: 'monospace', color: textMuted, lineHeight: 1.7 }}>
              <div><strong>ID:</strong> {event.id}</div>
              <div><strong>Тип:</strong> {event.type}</div>
              <div><strong>Борт:</strong> {event.aircraftId}</div>
              {event.flightNumber && <div><strong>Рейс:</strong> {event.flightNumber}</div>}
              {event.execId      && <div><strong>Выполнение:</strong> #{event.execId}</div>}
              <div><strong>Время:</strong> {event.timestamp}</div>
              {event.msgTemplate && <div><strong>Шаблон:</strong> {event.msgTemplate}</div>}
              {event.stepLabel   && <div><strong>Шаг:</strong> {event.stepLabel}</div>}
            </div>
          </div>
        )}
      </div>
    </div>
  );
});

TLEventCard.displayName = 'TLEventCard';
