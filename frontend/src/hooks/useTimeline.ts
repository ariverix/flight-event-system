import { useState, useEffect, useCallback, useRef } from 'react';
import api from '../api/axiosConfig';
import type { ExecutionInstanceResponse } from '../types/execution';
import type { MessageResponse } from '../types/message';

// ── ТИПЫ ──────────────────────────────────────────────
export type TLEventType =
  | 'MESSAGE_RECEIVED'
  | 'EXECUTION_STARTED'
  | 'STEP_COMPLETED'
  | 'EXECUTION_COMPLETED'
  | 'EXECUTION_FAILED';

export interface TLEvent {
  id:           string;
  type:         TLEventType;
  timestamp:    string;
  aircraftId:   string;
  flightNumber?: string;
  msgTemplate?:  string;
  msgDirection?: string;
  execId?:       number;
  seqName?:      string;
  stepNum?:      number;
  stepType?:     'ACTION' | 'WAIT' | 'EVALUATE';
  stepResult?:   'SUCCESS' | 'FAILURE';
  stepLabel?:    string;
}

// ── ТРАНСФОРМАЦИЯ ─────────────────────────────────────
const msgsToEvents = (msgs: MessageResponse[]): TLEvent[] =>
  msgs.map(m => ({
    id:           `msg-${m.id}`,
    type:         'MESSAGE_RECEIVED' as const,
    timestamp:    m.receivedAt,
    aircraftId:   m.aircraftId,
    flightNumber: m.flightNumber ?? undefined,
    msgTemplate:  m.templateName,
    msgDirection: m.messageType,
  }));

const execsToEvents = (execs: ExecutionInstanceResponse[]): TLEvent[] => {
  const evts: TLEvent[] = [];
  execs.forEach(ex => {
    evts.push({
      id:          `ex-start-${ex.id}`,
      type:        'EXECUTION_STARTED',
      timestamp:   ex.startedAt,
      aircraftId:  ex.aircraftId,
      flightNumber: ex.flightNumber ?? undefined,
      execId:      ex.id,
      seqName:     ex.sequenceName,
    });

    (ex.stepExecutions ?? [])
      .filter(s => s.result !== null)
      .forEach(s => {
        let stepLabel = `Шаг ${s.stepIndex}`;
        if (s.detailsJson) {
          try {
            const d = JSON.parse(s.detailsJson);
            stepLabel = d.actionType ?? d.label ?? stepLabel;
          } catch { /* ignore */ }
        }
        evts.push({
          id:          `step-${ex.id}-${s.stepIndex}`,
          type:        'STEP_COMPLETED',
          timestamp:   s.executedAt,
          aircraftId:  ex.aircraftId,
          flightNumber: ex.flightNumber ?? undefined,
          execId:      ex.id,
          seqName:     ex.sequenceName,
          stepNum:     s.stepIndex,
          stepType:    s.stepType as 'ACTION' | 'WAIT' | 'EVALUATE',
          stepResult:  s.result as 'SUCCESS' | 'FAILURE',
          stepLabel,
        });
      });

    if (ex.completedAt) {
      evts.push({
        id:          `ex-end-${ex.id}`,
        type:        ex.status === 'COMPLETED' ? 'EXECUTION_COMPLETED' : 'EXECUTION_FAILED',
        timestamp:   ex.completedAt,
        aircraftId:  ex.aircraftId,
        flightNumber: ex.flightNumber ?? undefined,
        execId:      ex.id,
        seqName:     ex.sequenceName,
      });
    }
  });
  return evts;
};

// ── ХУК ───────────────────────────────────────────────
export const useTimeline = (aircraftId: string | null) => {
  const [all,     setAll]     = useState<TLEvent[]>([]);
  const [visible, setVisible] = useState<TLEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState<string | null>(null);
  const [playing, setPlaying] = useState(false);
  const [idx,     setIdx]     = useState(0);
  const [speed,   setSpeed]   = useState(1);

  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    if (!aircraftId) return;
    setLoading(true);
    setError(null);
    try {
      const [msgRes, exRes] = await Promise.all([
        api.get('/messages',   { params: { aircraftId, size: 200, page: 0 } }),
        api.get('/executions', { params: { aircraftId, size: 200, page: 0 } }),
      ]);
      const msgs:  MessageResponse[]            = msgRes.data?.content ?? [];
      const execs: ExecutionInstanceResponse[]  = exRes.data?.content  ?? [];

      const events: TLEvent[] = [
        ...msgsToEvents(msgs),
        ...execsToEvents(execs),
      ].sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());

      setAll(events);
      setVisible(events);
      setIdx(events.length);
      setPlaying(false);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string };
      setError(e?.response?.data?.message ?? e?.message ?? 'Ошибка загрузки');
    } finally {
      setLoading(false);
    }
  }, [aircraftId]);

  useEffect(() => {
    load();
    return () => { if (timer.current) clearInterval(timer.current); };
  }, [load]);

  const pause = useCallback(() => {
    setPlaying(false);
    if (timer.current) { clearInterval(timer.current); timer.current = null; }
  }, []);

  const play = useCallback(() => {
    if (idx >= all.length && all.length > 0) {
      setVisible([]);
      setIdx(0);
    }
    setPlaying(true);
  }, [idx, all]);

  const reset  = useCallback(() => { pause(); setIdx(0); setVisible([]); }, [pause]);
  const toEnd  = useCallback(() => { pause(); setIdx(all.length); setVisible(all); }, [pause, all]);

  useEffect(() => {
    if (!playing) {
      if (timer.current) { clearInterval(timer.current); timer.current = null; }
      return;
    }
    const ms = Math.max(160, 900 / speed);
    timer.current = setInterval(() => {
      setIdx(prev => {
        if (prev >= all.length) {
          setPlaying(false);
          return prev;
        }
        const next = prev + 1;
        setVisible(all.slice(0, next));
        return next;
      });
    }, ms);
    return () => { if (timer.current) { clearInterval(timer.current); timer.current = null; } };
  }, [playing, all, speed]);

  return {
    all, visible, loading, error,
    playing, idx, speed,
    progress: all.length ? Math.min(100, (idx / all.length) * 100) : 0,
    total: all.length,
    shown: visible.length,
    play, pause, reset, toEnd,
    setSpeed,
    reload: load,
  };
};
