import React, { useState, useRef, useEffect } from 'react';
import { Select, Button, Skeleton, Alert, Badge } from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined,
  StepForwardOutlined, ReloadOutlined,
  ClockCircleOutlined, RadarChartOutlined,
} from '@ant-design/icons';
import { useTimeline } from '../hooks/useTimeline';
import { TLEventCard } from '../components/timeline/TLEventCard';
import { useTheme } from '../context/ThemeContext';

const AIRCRAFT = ['SU9876', 'RA-89050', 'SU1234', 'VP-BQR', 'CHECK-001'];
const SPEEDS   = [0.5, 1, 2, 4];

const HINTS = [
  { icon: '▶', text: 'Нажми "Воспроизвести" чтобы смотреть историю событий' },
  { icon: '⏸', text: '"Пауза" останавливает воспроизведение' },
  { icon: '4×', text: 'Выбери скорость 4× для быстрого просмотра' },
  { icon: '⏮', text: '"В начало" сбрасывает к первому событию' },
  { icon: '⏭', text: '"Показать все" переходит к текущему моменту' },
  { icon: '👆', text: 'Кликни на карточку события чтобы увидеть детали' },
];

const STAT_CONFIG = [
  { key: 'MESSAGE_RECEIVED',    label: 'Сообщений',  color: '#3b82f6' },
  { key: 'EXECUTION_STARTED',   label: 'Запусков',   color: '#10b981' },
  { key: 'STEP_COMPLETED',      label: 'Шагов',      color: '#8b5cf6' },
  { key: 'EXECUTION_COMPLETED', label: 'Завершено',  color: '#10b981' },
  { key: 'EXECUTION_FAILED',    label: 'Ошибок',     color: '#ef4444' },
];

export const TimelinePage: React.FC = () => {
  const { isDark } = useTheme();
  const [aircraft, setAircraft] = useState<string>(AIRCRAFT[0]);
  const scrollRef  = useRef<HTMLDivElement>(null);
  const prevShown  = useRef(0);

  const tl = useTimeline(aircraft);
  const [isNarrow, setIsNarrow] = useState(() => window.innerWidth < 900);

  useEffect(() => {
    const handle = () => setIsNarrow(window.innerWidth < 900);
    window.addEventListener('resize', handle);
    return () => window.removeEventListener('resize', handle);
  }, []);

  // Тема-адаптивные цвета
  const c = isDark
    ? {
        text:          'rgba(255,255,255,0.88)',
        textMuted:     'rgba(255,255,255,0.50)',
        textDim:       'rgba(255,255,255,0.30)',
        panelBg:       'rgba(255,255,255,0.042)',
        panelBorder:   'rgba(255,255,255,0.085)',
        statBg:        'rgba(255,255,255,0.028)',
        statBorder:    'rgba(255,255,255,0.075)',
        statLabel:     'rgba(255,255,255,0.50)',
        statDivider:   'rgba(255,255,255,0.055)',
        hintBg:        'rgba(59,130,246,0.055)',
        hintBorder:    'rgba(59,130,246,0.15)',
        hintTitle:     'rgba(59,130,246,0.65)',
        hintText:      'rgba(255,255,255,0.38)',
        headingColor:  'rgba(255,255,255,0.65)',
        progressTrack: 'rgba(255,255,255,0.08)',
        progressCount: 'rgba(255,255,255,0.35)',
        speedActive:   { bg:'rgba(59,130,246,0.22)', color:'#60a5fa', border:'rgba(59,130,246,0.48)' },
        speedInactive: { bg:'rgba(255,255,255,0.055)', color:'rgba(255,255,255,0.52)', border:'rgba(255,255,255,0.11)' },
        acftTag:       { bg:'rgba(59,130,246,0.12)', border:'rgba(59,130,246,0.30)', color:'#60a5fa' },
      }
    : {
        text:          '#1f2328',
        textMuted:     '#636c76',
        textDim:       '#9da3ab',
        panelBg:       'rgba(255,255,255,0.90)',
        panelBorder:   'rgba(0,0,0,0.09)',
        statBg:        'rgba(255,255,255,0.90)',
        statBorder:    'rgba(0,0,0,0.09)',
        statLabel:     '#636c76',
        statDivider:   'rgba(0,0,0,0.06)',
        hintBg:        'rgba(59,130,246,0.05)',
        hintBorder:    'rgba(59,130,246,0.15)',
        hintTitle:     'rgba(59,130,246,0.80)',
        hintText:      '#636c76',
        headingColor:  '#1f2328',
        progressTrack: 'rgba(0,0,0,0.08)',
        progressCount: 'rgba(0,0,0,0.40)',
        speedActive:   { bg:'rgba(59,130,246,0.10)', color:'#2563eb', border:'rgba(59,130,246,0.40)' },
        speedInactive: { bg:'rgba(0,0,0,0.04)', color:'rgba(0,0,0,0.55)', border:'rgba(0,0,0,0.12)' },
        acftTag:       { bg:'rgba(59,130,246,0.10)', border:'rgba(59,130,246,0.25)', color:'#2563eb' },
      };

  // Автоскролл к новым событиям
  useEffect(() => {
    if (tl.shown > prevShown.current) {
      setTimeout(() => {
        scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
      }, 50);
    }
    prevShown.current = tl.shown;
  }, [tl.shown]);

  const handleAircraftChange = (val: string) => {
    tl.reset();
    setAircraft(val);
  };

  return (
    <div className="fade-in-up">

      {/* ЗАГОЛОВОК */}
      <div style={{
        display: 'flex', justifyContent: 'space-between',
        alignItems: 'flex-start', marginBottom: 24,
        flexWrap: 'wrap', gap: 12,
      }}>
        <div>
          <h2 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
            <RadarChartOutlined style={{ color: '#3b82f6', fontSize: 22 }} />
            Хронология полёта
          </h2>
          <p style={{ color: c.textMuted, margin: 0, fontSize: 13 }}>
            Живая лента событий с воспроизведением истории
          </p>
        </div>

        <Select
          value={aircraft}
          onChange={handleAircraftChange}
          style={{ width: 180 }}
          options={AIRCRAFT.map(a => ({
            value: a,
            label: <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>✈ {a}</span>,
          }))}
        />
      </div>

      {/* ПАНЕЛЬ УПРАВЛЕНИЯ */}
      <div style={{
        background: c.panelBg,
        backdropFilter: 'blur(18px)',
        WebkitBackdropFilter: 'blur(18px)',
        border: `1px solid ${c.panelBorder}`,
        borderRadius: 16,
        padding: '14px 20px',
        marginBottom: 20,
        display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
        position: 'relative',
      }}>
        {/* Блик */}
        {isDark && (
          <div style={{
            position: 'absolute', top: 0, left: 0, right: 0, height: 1,
            background: 'linear-gradient(90deg,transparent,rgba(255,255,255,0.14) 50%,transparent)',
            pointerEvents: 'none', borderRadius: '16px 16px 0 0',
          }} />
        )}

        {/* Транспорт-кнопки */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button
            icon={<ReloadOutlined />}
            onClick={tl.reset}
            disabled={tl.shown === 0 && !tl.playing}
            title="В начало"
          />
          <Button
            type="primary"
            size="large"
            icon={tl.playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
            onClick={tl.playing ? tl.pause : tl.play}
            disabled={tl.total === 0}
            style={{
              minWidth: 150,
              background: tl.playing ? 'rgba(245,158,11,0.22)' : undefined,
              borderColor: tl.playing ? 'rgba(245,158,11,0.55)' : undefined,
              color: tl.playing ? '#f59e0b' : undefined,
            }}
          >
            {tl.playing ? 'Пауза' : 'Воспроизвести'}
          </Button>
          <Button
            icon={<StepForwardOutlined />}
            onClick={tl.toEnd}
            disabled={tl.shown === tl.total}
            title="Показать все"
          />
        </div>

        {/* Кнопки скорости */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 12, color: c.textMuted, whiteSpace: 'nowrap' }}>
            Скорость:
          </span>
          {SPEEDS.map(s => {
            const active = tl.speed === s;
            const sc = active ? c.speedActive : c.speedInactive;
            return (
              <button
                key={s}
                onClick={() => tl.setSpeed(s)}
                style={{
                  padding: '3px 12px', borderRadius: 20,
                  border: `1px solid ${sc.border}`,
                  background: sc.bg, color: sc.color,
                  fontSize: 12, fontWeight: 700,
                  cursor: 'pointer', userSelect: 'none',
                  transition: 'all 0.15s ease',
                  outline: 'none',
                }}
              >
                {s}×
              </button>
            );
          })}
        </div>

        {/* Прогресс */}
        <div style={{ flex: 1, minWidth: 160, display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            flex: 1, height: 5,
            background: c.progressTrack,
            borderRadius: 3, overflow: 'hidden',
          }}>
            <div style={{
              width: `${tl.progress}%`, height: '100%',
              background: 'linear-gradient(90deg,#3b82f6,#8b5cf6)',
              borderRadius: 3, transition: 'width 0.25s ease',
              boxShadow: '0 0 6px rgba(59,130,246,0.45)',
            }} />
          </div>
          <span style={{ fontSize: 12, color: c.progressCount, fontFamily: 'monospace', whiteSpace: 'nowrap' }}>
            {tl.shown}/{tl.total}
          </span>
        </div>

        {/* Перезагрузить */}
        <Button
          icon={<ReloadOutlined />}
          onClick={() => { tl.reset(); tl.reload(); }}
          loading={tl.loading}
          title="Перезагрузить данные"
        />
      </div>

      {/* Ошибка */}
      {tl.error && (
        <Alert message={tl.error} type="error" showIcon closable style={{ marginBottom: 16 }} />
      )}

      {/* ОСНОВНОЙ КОНТЕНТ */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: isNarrow ? '1fr' : '1fr 300px',
        gap: 20,
        alignItems: 'start',
      }}>
        {/* Лента событий */}
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
            <h3 style={{
              fontSize: 14, fontWeight: 600, color: c.headingColor, margin: 0,
              display: 'flex', alignItems: 'center', gap: 8,
            }}>
              {tl.playing && <Badge status="processing" />}
              События
            </h3>
            <span style={{
              fontFamily: 'monospace', fontWeight: 700, fontSize: 13,
              background: c.acftTag.bg, border: `1px solid ${c.acftTag.border}`,
              color: c.acftTag.color, borderRadius: 8, padding: '3px 12px',
            }}>
              ✈ {aircraft}
            </span>
          </div>

          <div ref={scrollRef} style={{ maxHeight: '68vh', overflowY: 'auto', paddingRight: 4 }}>
            {tl.loading && <Skeleton active paragraph={{ rows: 6 }} />}

            {!tl.loading && tl.total === 0 && !tl.error && (
              <div style={{ textAlign: 'center', padding: '64px 0', color: c.textDim }}>
                <ClockCircleOutlined style={{ fontSize: 48, opacity: 0.35, display: 'block', margin: '0 auto 16px' }} />
                <div style={{ fontSize: 15, marginBottom: 6 }}>Нет событий для борта {aircraft}</div>
                <div style={{ fontSize: 13 }}>Попробуйте другой борт или отправьте событие через Симулятор</div>
              </div>
            )}

            {!tl.loading && tl.total > 0 && tl.shown === 0 && (
              <div style={{ textAlign: 'center', padding: '64px 0', color: c.textDim }}>
                <PlayCircleOutlined style={{ fontSize: 48, opacity: 0.35, display: 'block', margin: '0 auto 16px' }} />
                <div style={{ fontSize: 15, marginBottom: 6 }}>Нажмите ▶ для воспроизведения</div>
                <div style={{ fontSize: 13 }}>{tl.total} событий в истории</div>
              </div>
            )}

            {tl.visible.map((ev, i) => (
              <TLEventCard
                key={ev.id}
                event={ev}
                isNew={i === tl.visible.length - 1 && tl.playing}
                showConnector={i < tl.visible.length - 1}
              />
            ))}
          </div>
        </div>

        {/* Статистика (sticky) */}
        <div style={{
          position: isNarrow ? 'relative' : 'sticky', top: 24,
          background: c.statBg,
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)',
          border: `1px solid ${c.statBorder}`,
          borderRadius: 16,
          padding: '20px',
        }}>
          <h3 style={{ fontSize: 13, fontWeight: 600, color: c.headingColor, margin: '0 0 14px' }}>
            Статистика
          </h3>

          {STAT_CONFIG.map(s => (
            <div key={s.key} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '10px 0',
              borderBottom: `1px solid ${c.statDivider}`,
            }}>
              <span style={{ fontSize: 13, color: c.statLabel }}>{s.label}</span>
              <span style={{ fontSize: 24, fontWeight: 700, color: s.color, letterSpacing: '-0.02em', transition: 'all 0.2s ease' }}>
                {tl.visible.filter(e => e.type === s.key).length}
              </span>
            </div>
          ))}

          {/* КАК ИСПОЛЬЗОВАТЬ */}
          <div style={{
            marginTop: 18, padding: '14px 16px',
            background: c.hintBg,
            border: `1px solid ${c.hintBorder}`,
            borderRadius: 12,
          }}>
            <div style={{
              fontSize: 10, fontWeight: 700, color: c.hintTitle,
              letterSpacing: '0.06em', textTransform: 'uppercase', marginBottom: 12,
            }}>
              Как использовать
            </div>
            {HINTS.map(h => (
              <div key={h.text} style={{
                display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 7,
              }}>
                <span style={{ fontSize: 13, flexShrink: 0, width: 22, textAlign: 'center', color: c.hintTitle }}>{h.icon}</span>
                <span style={{ fontSize: 12, color: c.hintText, lineHeight: 1.5 }}>{h.text}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export default TimelinePage;
