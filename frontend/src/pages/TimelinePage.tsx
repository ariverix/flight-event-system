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
import { useEditorI18n } from '../i18n/useEditorI18n';

const AIRCRAFT = ['SU9876', 'RA-89050', 'SU1234', 'VP-BQR', 'CHECK-001'];
const SPEEDS   = [0.5, 1, 2, 4];

export const TimelinePage: React.FC = () => {
  const { isDark } = useTheme();
  const d = useEditorI18n();

  const STAT_CONFIG = [
    { key: 'MESSAGE_RECEIVED',    label: d.tlStatMessages,               color: 'var(--accent-blue)' },
    { key: 'EXECUTION_STARTED',   label: d.tlStatStarts,                 color: 'var(--accent-green)' },
    { key: 'STEP_COMPLETED',      label: d.tlStatSteps,                  color: 'var(--accent-purple)' },
    { key: 'EXECUTION_COMPLETED', label: d.tlEventTypes.EXECUTION_COMPLETED, color: 'var(--accent-green)' },
    { key: 'EXECUTION_FAILED',    label: d.tlStatErrors,                 color: 'var(--accent-red)' },
  ];
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

  // Тема-адаптивные цвета — плоские поверхности (без glass-blur), системные
  // акценты через var(--accent-*) (см. TLEventCard.tsx)
  const c = isDark
    ? {
        text:          'rgba(255,255,255,0.88)',
        textMuted:     'rgba(255,255,255,0.50)',
        textDim:       'rgba(255,255,255,0.30)',
        panelBg:       '#262626',
        panelBorder:   'rgba(255,255,255,0.12)',
        statBg:        '#262626',
        statBorder:    'rgba(255,255,255,0.12)',
        statLabel:     'rgba(255,255,255,0.50)',
        statDivider:   'rgba(255,255,255,0.08)',
        headingColor:  'rgba(255,255,255,0.65)',
        progressTrack: 'rgba(255,255,255,0.08)',
        progressCount: 'rgba(255,255,255,0.35)',
        speedActive:   { bg:'rgba(var(--accent-blue-rgb), 0.20)', color:'var(--accent-blue)', border:'rgba(var(--accent-blue-rgb), 0.45)' },
        speedInactive: { bg:'rgba(255,255,255,0.055)', color:'rgba(255,255,255,0.52)', border:'rgba(255,255,255,0.11)' },
        acftTag:       { bg:'rgba(var(--accent-blue-rgb), 0.14)', border:'rgba(var(--accent-blue-rgb), 0.32)', color:'var(--accent-blue)' },
      }
    : {
        text:          '#1d1d1f',
        textMuted:     '#6e6e73',
        textDim:       '#8e8e93',
        panelBg:       '#ffffff',
        panelBorder:   'rgba(0,0,0,0.10)',
        statBg:        '#ffffff',
        statBorder:    'rgba(0,0,0,0.10)',
        statLabel:     '#6e6e73',
        statDivider:   'rgba(0,0,0,0.07)',
        headingColor:  '#1d1d1f',
        progressTrack: 'rgba(0,0,0,0.08)',
        progressCount: 'rgba(0,0,0,0.40)',
        speedActive:   { bg:'rgba(var(--accent-blue-rgb), 0.12)', color:'var(--accent-blue)', border:'rgba(var(--accent-blue-rgb), 0.35)' },
        speedInactive: { bg:'rgba(0,0,0,0.04)', color:'rgba(0,0,0,0.55)', border:'rgba(0,0,0,0.12)' },
        acftTag:       { bg:'rgba(var(--accent-blue-rgb), 0.10)', border:'rgba(var(--accent-blue-rgb), 0.28)', color:'var(--accent-blue)' },
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
            <RadarChartOutlined style={{ color: 'var(--accent-blue)', fontSize: 22 }} />
            {d.tlPageTitle}
          </h2>
          <p style={{ color: c.textMuted, margin: 0, fontSize: 13 }}>
            {d.tlPageSubtitle}
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
        border: `1px solid ${c.panelBorder}`,
        borderRadius: 12,
        padding: '14px 20px',
        marginBottom: 20,
        display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
        position: 'relative',
      }}>
        {/* Транспорт-кнопки */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button
            icon={<ReloadOutlined />}
            onClick={tl.reset}
            disabled={tl.shown === 0 && !tl.playing}
            title={d.tlToStartTooltip}
          />
          <Button
            type="primary"
            size="large"
            icon={tl.playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
            onClick={tl.playing ? tl.pause : tl.play}
            disabled={tl.total === 0}
            style={{
              minWidth: 150,
              background: tl.playing ? 'rgba(var(--accent-amber-rgb), 0.20)' : undefined,
              borderColor: tl.playing ? 'rgba(var(--accent-amber-rgb), 0.50)' : undefined,
              color: tl.playing ? 'var(--accent-amber)' : undefined,
            }}
          >
            {tl.playing ? d.tlPauseBtn : d.tlPlayBtn}
          </Button>
          <Button
            icon={<StepForwardOutlined />}
            onClick={tl.toEnd}
            disabled={tl.shown === tl.total}
            title={d.tlToEndTooltip}
          />
        </div>

        {/* Кнопки скорости */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 12, color: c.textMuted, whiteSpace: 'nowrap' }}>
            {d.tlSpeedLabel}
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
                  transition: 'background 0.15s ease, color 0.15s ease, border-color 0.15s ease, transform 0.12s var(--ease-out)',
                  outline: 'none',
                }}
                className="panel-btn-press"
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
              width: '100%', height: '100%',
              background: 'var(--accent-blue)',
              borderRadius: 3,
              transformOrigin: 'left',
              transform: `scaleX(${tl.progress / 100})`,
              transition: 'transform 0.25s ease',
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
          title={d.tlReloadTooltip}
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
              {d.tlEventsHeading}
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
                <div style={{ fontSize: 15, marginBottom: 6 }}>{d.tlEmptyNoEventsPrefix} {aircraft}</div>
                <div style={{ fontSize: 13 }}>{d.tlEmptyNoEventsHint}</div>
              </div>
            )}

            {!tl.loading && tl.total > 0 && tl.shown === 0 && (
              <div style={{ textAlign: 'center', padding: '64px 0', color: c.textDim }}>
                <PlayCircleOutlined style={{ fontSize: 48, opacity: 0.35, display: 'block', margin: '0 auto 16px' }} />
                <div style={{ fontSize: 15, marginBottom: 6 }}>{d.tlEmptyPressPlay}</div>
                <div style={{ fontSize: 13 }}>{tl.total} {d.tlEventsInHistorySuffix}</div>
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
          border: `1px solid ${c.statBorder}`,
          borderRadius: 12,
          padding: '20px',
        }}>
          <h3 style={{ fontSize: 13, fontWeight: 600, color: c.headingColor, margin: '0 0 14px' }}>
            {d.tlStatsHeading}
          </h3>

          {STAT_CONFIG.map(s => {
            const count = tl.visible.filter(e => e.type === s.key).length;
            return (
              <div key={s.key} style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                padding: '10px 0',
                borderBottom: `1px solid ${c.statDivider}`,
              }}>
                <span style={{ fontSize: 13, color: c.statLabel }}>{s.label}</span>
                {/* key={count} — ремонтирует span при каждом инкременте, чтобы countPop
                    проигрывался заново (CSS animation не перезапускается на том же узле) */}
                <span
                  key={count}
                  className="stat-count-pop"
                  style={{ fontSize: 24, fontWeight: 700, color: s.color, letterSpacing: '-0.02em' }}
                >
                  {count}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default TimelinePage;
