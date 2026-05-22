import React, { useState, useRef, useEffect } from 'react';
import { Select, Button, Tooltip, Badge, Skeleton, Tag, Space, Alert } from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined,
  StepForwardOutlined, ReloadOutlined,
  ClockCircleOutlined, RadarChartOutlined,
} from '@ant-design/icons';
import { useTimeline } from '../hooks/useTimeline';
import { TLEventCard } from '../components/timeline/TLEventCard';

const AIRCRAFT = ['SU9876', 'RA-89050', 'SU1234', 'VP-BQR', 'CHECK-001'];

const SPEEDS = [
  { v: 0.5, l: '0.5×' },
  { v: 1,   l: '1×'   },
  { v: 2,   l: '2×'   },
  { v: 4,   l: '4×'   },
];

export const TimelinePage: React.FC = () => {
  const [aircraft, setAircraft] = useState<string>(AIRCRAFT[0]);
  const scrollRef  = useRef<HTMLDivElement>(null);
  const prevShown  = useRef(0);

  const tl = useTimeline(aircraft);

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
          <h2 className="page-title" style={{
            display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4,
          }}>
            <RadarChartOutlined style={{ color: '#3b82f6', fontSize: 22 }} />
            Хронология полёта
          </h2>
          <p style={{ color: 'var(--text-3)', margin: 0, fontSize: 13 }}>
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
        background: 'rgba(255,255,255,0.042)',
        backdropFilter: 'blur(18px)',
        border: '1px solid rgba(255,255,255,0.085)',
        borderRadius: 16,
        padding: '14px 20px',
        marginBottom: 20,
        display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
        position: 'relative', overflow: 'hidden',
      }}>
        {/* Блик */}
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, height: 1,
          background: 'linear-gradient(90deg,transparent,rgba(255,255,255,0.14) 50%,transparent)',
          pointerEvents: 'none',
        }} />

        {/* Транспорт */}
        <Space>
          <Tooltip title="В начало">
            <Button icon={<ReloadOutlined />} onClick={tl.reset} disabled={tl.shown === 0 && !tl.playing} />
          </Tooltip>
          <Button
            type="primary"
            size="large"
            icon={tl.playing ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
            onClick={tl.playing ? tl.pause : tl.play}
            disabled={tl.total === 0}
            style={{
              minWidth: 140,
              background: tl.playing ? 'rgba(245,158,11,0.22)' : undefined,
              borderColor: tl.playing ? 'rgba(245,158,11,0.55)' : undefined,
            }}
          >
            {tl.playing ? 'Пауза' : 'Воспроизвести'}
          </Button>
          <Tooltip title="Показать все">
            <Button
              icon={<StepForwardOutlined />}
              onClick={tl.toEnd}
              disabled={tl.shown === tl.total}
            />
          </Tooltip>
        </Space>

        {/* Скорость */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.40)', whiteSpace: 'nowrap' }}>
            Скорость:
          </span>
          {SPEEDS.map(s => (
            <Tag
              key={s.v}
              onClick={() => tl.setSpeed(s.v)}
              style={{
                cursor: 'pointer', fontWeight: 700, borderRadius: 8,
                padding: '2px 10px', fontSize: 12, userSelect: 'none',
                background: tl.speed === s.v ? 'rgba(59,130,246,0.22)' : 'rgba(255,255,255,0.055)',
                border: tl.speed === s.v ? '1px solid rgba(59,130,246,0.48)' : '1px solid rgba(255,255,255,0.11)',
                color: tl.speed === s.v ? '#60a5fa' : 'rgba(255,255,255,0.52)',
                transition: 'all 0.18s ease',
              }}
            >
              {s.l}
            </Tag>
          ))}
        </div>

        {/* Прогресс-бар */}
        <div style={{ flex: 1, minWidth: 160, display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            flex: 1, height: 4, background: 'rgba(255,255,255,0.08)',
            borderRadius: 2, overflow: 'hidden',
          }}>
            <div style={{
              width: `${tl.progress}%`, height: '100%',
              background: 'linear-gradient(90deg,#3b82f6,#8b5cf6)',
              borderRadius: 2,
              transition: 'width 0.25s ease',
              boxShadow: '0 0 8px rgba(59,130,246,0.55)',
            }} />
          </div>
          <span style={{
            fontSize: 12, color: 'rgba(255,255,255,0.35)',
            fontFamily: 'monospace', whiteSpace: 'nowrap',
          }}>
            {tl.shown}/{tl.total}
          </span>
        </div>

        <Tooltip title="Перезагрузить данные">
          <Button
            icon={<ReloadOutlined />}
            onClick={() => { tl.reset(); tl.reload(); }}
            loading={tl.loading}
          />
        </Tooltip>
      </div>

      {/* Ошибка */}
      {tl.error && (
        <Alert
          message={tl.error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {/* ОСНОВНОЙ КОНТЕНТ */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '1fr 300px',
        gap: 20,
        alignItems: 'start',
      }}>
        {/* Лента событий */}
        <div>
          <div style={{
            display: 'flex', justifyContent: 'space-between',
            alignItems: 'center', marginBottom: 14,
          }}>
            <h3 style={{
              fontSize: 14, fontWeight: 600,
              color: 'rgba(255,255,255,0.65)', margin: 0,
              display: 'flex', alignItems: 'center', gap: 8,
            }}>
              {tl.playing && <Badge status="processing" />}
              События
            </h3>
            <Tag style={{
              fontFamily: 'monospace', fontWeight: 700, fontSize: 13,
              background: 'rgba(59,130,246,0.12)',
              border: '1px solid rgba(59,130,246,0.30)',
              color: '#60a5fa', borderRadius: 8, padding: '3px 12px',
            }}>
              ✈ {aircraft}
            </Tag>
          </div>

          <div ref={scrollRef} style={{ maxHeight: '68vh', overflowY: 'auto', paddingRight: 4 }}>

            {tl.loading && <Skeleton active paragraph={{ rows: 6 }} />}

            {!tl.loading && tl.total === 0 && !tl.error && (
              <div style={{ textAlign: 'center', padding: '64px 0', color: 'rgba(255,255,255,0.28)' }}>
                <ClockCircleOutlined style={{ fontSize: 48, opacity: 0.35, display: 'block', margin: '0 auto 16px' }} />
                <div style={{ fontSize: 15, marginBottom: 6 }}>Нет событий для борта {aircraft}</div>
                <div style={{ fontSize: 13, opacity: 0.7 }}>Попробуйте другой борт или отправьте тестовое событие через Симулятор</div>
              </div>
            )}

            {!tl.loading && tl.total > 0 && tl.shown === 0 && (
              <div style={{ textAlign: 'center', padding: '64px 0', color: 'rgba(255,255,255,0.28)' }}>
                <PlayCircleOutlined style={{ fontSize: 48, opacity: 0.35, display: 'block', margin: '0 auto 16px' }} />
                <div style={{ fontSize: 15, marginBottom: 6 }}>Нажмите ▶ для воспроизведения</div>
                <div style={{ fontSize: 13, opacity: 0.7 }}>{tl.total} событий в истории</div>
              </div>
            )}

            {tl.visible.map((ev, i) => (
              <TLEventCard
                key={ev.id}
                event={ev}
                isNew={i === tl.visible.length - 1 && tl.playing}
              />
            ))}
          </div>
        </div>

        {/* Статистика (sticky) */}
        <div style={{
          position: 'sticky', top: 24,
          background: 'rgba(255,255,255,0.028)',
          backdropFilter: 'blur(16px)',
          border: '1px solid rgba(255,255,255,0.075)',
          borderRadius: 16,
          padding: '20px',
        }}>
          <h3 style={{ fontSize: 13, fontWeight: 600, color: 'rgba(255,255,255,0.60)', margin: '0 0 14px' }}>
            Статистика
          </h3>

          {[
            { l: 'Сообщений',  c: tl.visible.filter(e => e.type === 'MESSAGE_RECEIVED').length,    col: '#3b82f6' },
            { l: 'Запусков',   c: tl.visible.filter(e => e.type === 'EXECUTION_STARTED').length,   col: '#10b981' },
            { l: 'Шагов',      c: tl.visible.filter(e => e.type === 'STEP_COMPLETED').length,      col: '#8b5cf6' },
            { l: 'Завершено',  c: tl.visible.filter(e => e.type === 'EXECUTION_COMPLETED').length, col: '#10b981' },
            { l: 'Ошибок',     c: tl.visible.filter(e => e.type === 'EXECUTION_FAILED').length,    col: '#ef4444' },
          ].map(s => (
            <div key={s.l} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '9px 0',
              borderBottom: '1px solid rgba(255,255,255,0.055)',
            }}>
              <span style={{ fontSize: 13, color: 'rgba(255,255,255,0.48)' }}>{s.l}</span>
              <span style={{
                fontSize: 22, fontWeight: 700, color: s.col,
                letterSpacing: '-0.02em',
                transition: 'all 0.2s ease',
              }}>
                {s.c}
              </span>
            </div>
          ))}

          {/* Подсказки */}
          <div style={{
            marginTop: 16, padding: '12px 14px',
            background: 'rgba(59,130,246,0.055)',
            border: '1px solid rgba(59,130,246,0.15)',
            borderRadius: 12,
          }}>
            <div style={{
              fontSize: 10, fontWeight: 700,
              color: 'rgba(59,130,246,0.65)',
              letterSpacing: '0.06em', textTransform: 'uppercase',
              marginBottom: 10,
            }}>
              Как использовать
            </div>
            {[
              '▶ Воспроизвести — смотреть историю',
              '4× — быстрый обзор событий',
              '⏮ Сброс — начать с начала',
              '⏭ Показать все — текущий момент',
            ].map(h => (
              <div key={h} style={{
                fontSize: 11, color: 'rgba(255,255,255,0.36)',
                marginBottom: 6, paddingLeft: 12, position: 'relative', lineHeight: 1.4,
              }}>
                <div style={{
                  position: 'absolute', left: 0, top: '50%',
                  transform: 'translateY(-50%)',
                  width: 4, height: 4, borderRadius: '50%',
                  background: 'rgba(59,130,246,0.55)',
                }} />
                {h}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export default TimelinePage;
