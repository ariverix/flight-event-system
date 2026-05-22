import React, { memo } from 'react';
import { Tag } from 'antd';
import {
  MessageOutlined, PlayCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined,
  ThunderboltOutlined, ClockCircleOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import type { TLEvent, TLEventType } from '../../hooks/useTimeline';

interface CfgEntry {
  icon: React.ReactNode;
  color: string;
  bg: string;
  bd: string;
  label: string;
  glow: string;
}

const CONFIG: Record<TLEventType, CfgEntry> = {
  MESSAGE_RECEIVED:    { icon:<MessageOutlined />,      color:'#3b82f6', bg:'rgba(59,130,246,0.10)',  bd:'rgba(59,130,246,0.22)',  label:'Сообщение',  glow:'0 0 16px rgba(59,130,246,0.40)'  },
  EXECUTION_STARTED:   { icon:<PlayCircleOutlined />,   color:'#10b981', bg:'rgba(16,185,129,0.10)',  bd:'rgba(16,185,129,0.22)',  label:'Запуск',     glow:'0 0 16px rgba(16,185,129,0.40)'  },
  STEP_COMPLETED:      { icon:<ThunderboltOutlined />,  color:'#8b5cf6', bg:'rgba(139,92,246,0.10)',  bd:'rgba(139,92,246,0.22)',  label:'Шаг',        glow:'0 0 16px rgba(139,92,246,0.40)'  },
  EXECUTION_COMPLETED: { icon:<CheckCircleOutlined />,  color:'#10b981', bg:'rgba(16,185,129,0.12)',  bd:'rgba(16,185,129,0.28)',  label:'Завершено',  glow:'0 0 16px rgba(16,185,129,0.42)'  },
  EXECUTION_FAILED:    { icon:<CloseCircleOutlined />,  color:'#ef4444', bg:'rgba(239,68,68,0.12)',   bd:'rgba(239,68,68,0.28)',   label:'Ошибка',     glow:'0 0 16px rgba(239,68,68,0.40)'   },
};

const fmt = (iso: string) =>
  new Date(iso).toLocaleTimeString('ru-RU', { hour:'2-digit', minute:'2-digit', second:'2-digit' });

const MSG_DIR_LABEL: Record<string, string> = {
  GROUND: 'Наземная', DOWNLINK: 'Нисходящая', UPLINK: 'Восходящая',
};

const StepIcon: React.FC<{ t?: string }> = ({ t }) => {
  const s: React.CSSProperties = { fontSize: 10 };
  if (t === 'WAIT')     return <ClockCircleOutlined style={s} />;
  if (t === 'EVALUATE') return <QuestionCircleOutlined style={s} />;
  return <ThunderboltOutlined style={s} />;
};

interface Props { event: TLEvent; isNew?: boolean; }

const tagStyle = (bg: string, bd: string, color: string): React.CSSProperties => ({
  marginLeft: 8, fontSize: 10, fontWeight: 600, borderRadius: 6,
  background: bg, border: `1px solid ${bd}`, color,
});

export const TLEventCard = memo(({ event, isNew = false }: Props) => {
  const cfg = CONFIG[event.type];

  const renderBody = () => {
    switch (event.type) {
      case 'MESSAGE_RECEIVED':
        return (
          <span>
            <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'rgba(255,255,255,0.88)' }}>
              {event.msgTemplate ?? '—'}
            </span>
            {event.msgDirection && (
              <Tag style={tagStyle('rgba(59,130,246,0.12)', 'rgba(59,130,246,0.25)', '#60a5fa')}>
                {MSG_DIR_LABEL[event.msgDirection] ?? event.msgDirection}
              </Tag>
            )}
          </span>
        );

      case 'EXECUTION_STARTED':
        return (
          <span style={{ color: 'rgba(255,255,255,0.82)' }}>
            Запущена: <strong>{event.seqName}</strong>
            <Tag style={tagStyle('rgba(16,185,129,0.10)', 'rgba(16,185,129,0.25)', '#6ee7b7')}>
              #{event.execId}
            </Tag>
          </span>
        );

      case 'STEP_COMPLETED':
        return (
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.45)' }}>
              Шаг {event.stepNum}
            </span>
            {event.stepLabel && (
              <span style={{ fontSize: 12, fontFamily: 'monospace', color: 'rgba(255,255,255,0.72)' }}>
                {event.stepLabel}
              </span>
            )}
            <Tag style={tagStyle(
              event.stepResult === 'SUCCESS' ? 'rgba(16,185,129,0.10)' : 'rgba(239,68,68,0.10)',
              event.stepResult === 'SUCCESS' ? 'rgba(16,185,129,0.25)' : 'rgba(239,68,68,0.25)',
              event.stepResult === 'SUCCESS' ? '#6ee7b7' : '#fca5a5',
            )}>
              <StepIcon t={event.stepType} />
              {' '}{event.stepResult === 'SUCCESS' ? 'Успех' : 'Ошибка'}
            </Tag>
          </span>
        );

      case 'EXECUTION_COMPLETED':
        return (
          <span style={{ color: 'rgba(255,255,255,0.82)' }}>
            <CheckCircleOutlined style={{ color: '#10b981', marginRight: 6 }} />
            Завершена: <strong>{event.seqName}</strong>
          </span>
        );

      case 'EXECUTION_FAILED':
        return (
          <span style={{ color: 'rgba(255,255,255,0.82)' }}>
            <CloseCircleOutlined style={{ color: '#ef4444', marginRight: 6 }} />
            Ошибка: <strong>{event.seqName}</strong>
          </span>
        );

      default:
        return null;
    }
  };

  return (
    <div style={{
      display: 'flex', gap: 14, paddingBottom: 16,
      animation: isNew ? 'fadeInUp 0.32s ease both' : 'none',
      position: 'relative',
    }}>
      {/* Вертикальная линия */}
      <div style={{
        position: 'absolute', left: 16, top: 34, bottom: 0,
        width: 2,
        background: isNew ? cfg.bd : 'rgba(255,255,255,0.06)',
        borderRadius: 1,
        transition: 'background 0.3s ease',
        pointerEvents: 'none',
      }} />

      {/* Иконка */}
      <div style={{
        width: 34, height: 34, borderRadius: '50%',
        background: cfg.bg,
        border: `1.5px solid ${cfg.bd}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: cfg.color, fontSize: 14, flexShrink: 0, zIndex: 1,
        boxShadow: isNew ? cfg.glow : 'none',
        transition: 'box-shadow 0.3s ease',
      }}>
        {cfg.icon}
      </div>

      {/* Карточка */}
      <div style={{
        flex: 1,
        background: 'rgba(255,255,255,0.030)',
        border: `1px solid ${isNew ? cfg.bd : 'rgba(255,255,255,0.065)'}`,
        borderRadius: 12,
        padding: '10px 14px',
        backdropFilter: 'blur(8px)',
        transition: 'border-color 0.4s ease',
        minWidth: 0,
      }}>
        <div style={{
          display: 'flex', justifyContent: 'space-between',
          alignItems: 'center', marginBottom: 6,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{
              fontSize: 10, fontWeight: 700, color: cfg.color,
              letterSpacing: '0.06em', textTransform: 'uppercase',
            }}>
              {cfg.label}
            </span>
            {event.flightNumber && (
              <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.28)', fontFamily: 'monospace' }}>
                {event.flightNumber}
              </span>
            )}
          </div>
          <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.28)', fontFamily: 'monospace', flexShrink: 0 }}>
            {fmt(event.timestamp)}
          </span>
        </div>

        <div style={{ fontSize: 13, lineHeight: 1.5 }}>{renderBody()}</div>
      </div>
    </div>
  );
});

TLEventCard.displayName = 'TLEventCard';
