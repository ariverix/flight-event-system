import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Card, Tag, Typography, Button, Divider, Space, Select } from 'antd';
import {
  PlayCircleOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ThunderboltOutlined,
  SendOutlined,
  RightOutlined,
  ReloadOutlined,
  NodeExpandOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { messageApi } from '../../api/messageApi';
import { sequenceApi } from '../../api/sequenceApi';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse } from '../../types/execution';
import { SequenceResponse } from '../../types/sequence';
import { useTheme } from '../../context/ThemeContext';
import { ExecutionFlow } from '../execution/ExecutionFlow';
import { getTypeAccent } from '../../utils/stepTypeColors';

const { Text } = Typography;

type DemoPhase = 'idle' | 'activating' | 'triggering' | 'waiting' | 'polling' | 'done' | 'error';

interface LogEntry {
  time: string;
  text: string;
  type: 'info' | 'success' | 'warn' | 'error';
}

interface Scenario {
  key: string;
  title: string;
  seqName: string;
  aircraft: string;
  flight: string;
  ucRefs: string[];
  description: string;
  trigger: { type: 'stage' | 'message'; payload: any };
  followUp?: { delayMs: number; payload: any; label: string };
  steps: { label: string; type: 'ACTION' | 'WAIT' | 'EVALUATE' }[];
  emoji: string;
}

const SCENARIOS: Scenario[] = [
  {
    key: 'weather',
    title: 'Распределение метеоинформации',
    seqName: 'Распределение метеоинформации',
    aircraft: 'SU9876',
    flight: 'AFL123',
    ucRefs: ['UC-06'],
    emoji: '🌤',
    description: 'Мгновенное выполнение — все 3 ACTION-шага завершаются за секунды.',
    trigger: {
      type: 'message',
      payload: { messageType: 'GROUND', templateName: 'WEATHER_UPDATE', aircraftId: 'SU9876', flightNumber: 'AFL123', metadataJson: '{"temperature":-5,"wind":"270/10kt"}' },
    },
    steps: [
      { label: 'Переслать метеосводку экипажу', type: 'ACTION' },
      { label: 'Уведомить диспетчерскую', type: 'ACTION' },
      { label: 'WEATHER_ADVISORY_SENT', type: 'ACTION' },
    ],
  },
  {
    key: 'delay',
    title: 'Уведомление о задержке рейса',
    seqName: 'Уведомление о задержке рейса',
    aircraft: 'SU9876',
    flight: 'AFL123',
    ucRefs: ['UC-06', 'UC-07'],
    emoji: '⏱',
    description: 'EVALUATE-шаг: при первом запуске алерта нет → цепочка. При повторном → END.',
    trigger: {
      type: 'message',
      payload: { messageType: 'GROUND', templateName: 'DELAY_NOTICE', aircraftId: 'SU9876', flightNumber: 'AFL123', metadataJson: '{"delayMinutes":45}' },
    },
    steps: [
      { label: 'EVALUATE: алерт активен?', type: 'EVALUATE' },
      { label: 'Подтвердить уведомление', type: 'ACTION' },
      { label: 'Поднять алерт FLIGHT_DELAYED', type: 'ACTION' },
    ],
  },
  {
    key: 'landing',
    title: 'Контроль связи после посадки',
    seqName: 'Контроль связи после посадки',
    aircraft: 'SU9876',
    flight: 'AFL123',
    ucRefs: ['UC-06', 'UC-08'],
    emoji: '✈',
    description: 'WAIT-шаг 30 сек — нет ответа экипажа → автозапрос контакта + алерт.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'SU9876', flightNumber: 'AFL123', newStage: 'ON' },
    },
    steps: [
      { label: 'WAIT 30 сек: LANDING_REPORT', type: 'WAIT' },
      { label: 'Запросить CONTACT_REQUEST', type: 'ACTION' },
      { label: 'Алерт NO_LANDING_CONTACT', type: 'ACTION' },
    ],
  },
  {
    key: 'preflight',
    title: 'Предполётная подготовка',
    seqName: 'Предполётная подготовка',
    aircraft: 'SU1234',
    flight: 'AFL456',
    ucRefs: ['UC-06', 'UC-08'],
    emoji: '🛫',
    description: 'Чеклист → WAIT → авто-ответ через 4 сек → COMPLETED.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'SU1234', flightNumber: 'AFL456', newStage: 'INIT' },
    },
    followUp: {
      delayMs: 4000,
      label: 'Экипаж: PREFLIGHT_COMPLETE',
      payload: { messageType: 'DOWNLINK', templateName: 'PREFLIGHT_COMPLETE', aircraftId: 'SU1234', flightNumber: 'AFL456', metadataJson: '{"pilot":"Иванов"}' },
    },
    steps: [
      { label: 'Отправить чеклист экипажу', type: 'ACTION' },
      { label: 'WAIT 30 сек: PREFLIGHT_COMPLETE', type: 'WAIT' },
      { label: 'Алерт: PREFLIGHT_TIMEOUT', type: 'ACTION' },
    ],
  },
  {
    key: 'full',
    title: 'Презентационный сценарий ECA',
    seqName: 'Презентационный сценарий ECA',
    aircraft: 'CHECK-001',
    flight: 'DEMO100',
    ucRefs: ['UC-06', 'UC-07', 'UC-08'],
    emoji: '🎬',
    description: '7 шагов подряд: SEND_UPLINK, SEND_GROUND, RAISE_CONDITION, реальный WAIT (до 10 сек — видно автообновление статуса), EVALUATE подтверждения DEMO_ACK, CLOSE_CONDITION и финальный RAISE_CONDITION — для презентационного видео.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'CHECK-001', flightNumber: 'DEMO100', newStage: 'OFF' },
    },
    followUp: {
      delayMs: 4000,
      label: 'Экипаж: DEMO_ACK',
      payload: { messageType: 'DOWNLINK', templateName: 'DEMO_ACK', aircraftId: 'CHECK-001', flightNumber: 'DEMO100', metadataJson: '{"confirmed":true}' },
    },
    steps: [
      { label: 'Приветствие экипажу (SEND_UPLINK)', type: 'ACTION' },
      { label: 'Уведомить диспетчерскую (SEND_GROUND)', type: 'ACTION' },
      { label: 'Поднять алерт DEMO_MODE', type: 'ACTION' },
      { label: 'WAIT до 10 сек: ожидание DEMO_ACK', type: 'WAIT' },
      { label: 'EVALUATE: подтверждение DEMO_ACK получено?', type: 'EVALUATE' },
      { label: 'Снять алерт DEMO_MODE (CLOSE_CONDITION)', type: 'ACTION' },
      { label: 'Зафиксировать завершение демо (DEMO_COMPLETE)', type: 'ACTION' },
    ],
  },
  {
    key: 'position',
    title: 'Запрос позиционного отчёта',
    seqName: 'Запрос позиционного отчёта после взлёта',
    aircraft: 'RA-89050',
    flight: 'SU777',
    ucRefs: ['UC-06', 'UC-08'],
    emoji: '📡',
    description: 'Взлёт (OFF) → ждём 10 сек → EVALUATE позиции → SEND_UPLINK → WAIT POSITION_REPORT.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'RA-89050', flightNumber: 'SU777', newStage: 'OFF' },
    },
    steps: [
      { label: 'WAIT_TIME: 10 сек', type: 'ACTION' },
      { label: 'EVALUATE: позиция получена?', type: 'EVALUATE' },
      { label: 'REQUEST_POSITION uplink', type: 'ACTION' },
      { label: 'WAIT: POSITION_REPORT', type: 'WAIT' },
      { label: 'Алерт NO_POSITION_30MIN', type: 'ACTION' },
    ],
  },
];

const STATUS_LABEL: Record<string, string> = { RUNNING: 'Выполняется', WAITING: 'Ожидание', COMPLETED: 'Завершено', ABORTED: 'Прервано' };
const STATUS_COLOR_TAG: Record<string, string> = { RUNNING: 'processing', WAITING: 'warning', COMPLETED: 'success', ABORTED: 'error' };

function ts() { return new Date().toLocaleTimeString('ru-RU'); }

export const DemoPage: React.FC = () => {
  const navigate = useNavigate();
  const { isDark } = useTheme();
  const [scenarioKey, setScenarioKey] = useState('full');
  const [phase, setPhase] = useState<DemoPhase>('idle');
  const [log, setLog] = useState<LogEntry[]>([]);
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [showGraph, setShowGraph] = useState(true);
  const [followUpSent, setFollowUpSent] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const logRef = useRef<HTMLDivElement>(null);

  const c = isDark
    ? { border: 'rgba(255,255,255,0.14)', borderSec: 'rgba(255,255,255,0.09)', text: '#f5f5f7', muted: 'rgba(255,255,255,0.55)', dim: 'rgba(255,255,255,0.30)', bg: '#2c2c2e', bgLow: '#1e1e1e', logText: 'rgba(255,255,255,0.55)' }
    : { border: 'rgba(0,0,0,0.14)', borderSec: 'rgba(0,0,0,0.08)', text: '#1d1d1f', muted: '#6e6e73', dim: '#8e8e93', bg: '#ffffff', bgLow: '#f5f5f7', logText: '#6e6e73' };

  const scenario = SCENARIOS.find(s => s.key === scenarioKey)!;

  const addLog = useCallback((text: string, type: LogEntry['type'] = 'info') => {
    setLog(prev => [...prev, { time: ts(), text, type }]);
  }, []);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [log]);

  const stopPoll = useCallback(() => {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
  }, []);

  const reset = useCallback(() => {
    stopPoll();
    setPhase('idle');
    setLog([]);
    setExecution(null);
    setSequence(null);
    setFollowUpSent(false);
  }, [stopPoll]);

  useEffect(() => { return () => stopPoll(); }, [stopPoll]);

  async function runDemo() {
    reset();
    const sc = SCENARIOS.find(s => s.key === scenarioKey)!;
    setPhase('activating');

    addLog(`🔍 Ищем последовательность: «${sc.seqName}»…`);
    let seqId: number | null = null;
    let foundSeq: SequenceResponse | null = null;

    try {
      const page = await sequenceApi.getSequences(0, 50);
      foundSeq = page.content.find(s => s.name === sc.seqName) ?? null;
      if (!foundSeq) {
        addLog(`❌ Не найдена. Проверьте что миграция V11 применена.`, 'error');
        setPhase('error'); return;
      }
      seqId = foundSeq.id;
      setSequence(foundSeq);
      addLog(`✓ Найдена: ID=${foundSeq.id}, статус=${foundSeq.status}`, 'success');

      if (foundSeq.status !== 'ACTIVE') {
        addLog(`⚡ Активируем…`);
        await sequenceApi.activateSequence(foundSeq.id);
        // refresh to get updated status
        foundSeq = await sequenceApi.getSequenceById(foundSeq.id);
        setSequence(foundSeq);
        addLog(`✓ Статус → ACTIVE`, 'success');
      } else {
        addLog(`✓ Уже активна`, 'success');
      }
    } catch (e: any) {
      addLog(`❌ ${e.response?.data?.message ?? e.message}`, 'error');
      setPhase('error'); return;
    }

    let maxExistingId = 0;
    try {
      const ex = await executionApi.getExecutions(0, 1, undefined, sc.aircraft);
      if (ex.content.length) maxExistingId = ex.content[0].id;
    } catch { /* ignore */ }

    setPhase('triggering');
    addLog(`📡 Отправляем триггерное событие…`);
    try {
      if (sc.trigger.type === 'stage') {
        await messageApi.changeFlightStage(sc.trigger.payload);
        addLog(`✓ FlightStage → ${sc.trigger.payload.newStage} (${sc.aircraft})`, 'success');
      } else {
        await messageApi.sendMessage(sc.trigger.payload);
        addLog(`✓ Сообщение ${sc.trigger.payload.templateName} отправлено`, 'success');
      }
    } catch (e: any) {
      addLog(`❌ ${e.response?.data?.message ?? e.message}`, 'error');
      setPhase('error'); return;
    }

    setPhase('waiting');
    addLog(`⏳ Ждём запуска выполнения…`);
    let foundExec: ExecutionInstanceResponse | null = null;
    let attempts = 0;

    await new Promise<void>(resolve => {
      pollRef.current = setInterval(async () => {
        attempts++;
        try {
          const page = await executionApi.getExecutions(0, 20, undefined, sc.aircraft);
          const match = page.content.find(e => e.sequenceId === seqId && e.id > maxExistingId);
          if (match) { foundExec = match; stopPoll(); resolve(); }
          else if (attempts > 20) { stopPoll(); resolve(); }
        } catch { /* ignore */ }
      }, 1000);
    });

    if (!foundExec) {
      addLog(`⚠ Выполнение не появилось за 20 сек.`, 'warn');
      setPhase('error'); return;
    }

    // Показываем выполнение как «только начатое» — без шагов, чтобы дальнейший
    // поэтапный реплей (ниже) не выглядел перемоткой назад к началу.
    setExecution({ ...(foundExec as ExecutionInstanceResponse), stepExecutions: [], currentStepIndex: 1 });
    addLog(`🚀 Выполнение запущено! ID=${(foundExec as ExecutionInstanceResponse).id}`, 'success');

    if (sc.followUp && !followUpSent) {
      setTimeout(async () => {
        try {
          addLog(`→ ${sc.followUp!.label}`, 'info');
          await messageApi.sendMessage(sc.followUp!.payload);
          addLog(`✓ Отправлено: ${sc.followUp!.payload.templateName}`, 'success');
          setFollowUpSent(true);
        } catch { /* ignore */ }
      }, sc.followUp.delayMs);
    }

    const execId = (foundExec as ExecutionInstanceResponse).id;
    let prevStepCount = 0;
    let busy = false;
    setPhase('polling');

    // Шаги, выполненные сервером "пачкой" (без видимой задержки между ними),
    // показываем в журнале и на графе по одному с искусственной паузой —
    // иначе вся демонстрация мелькает за доли секунды.
    const STEP_REVEAL_DELAY_MS = 800;

    await new Promise<void>(resolve => {
      pollRef.current = setInterval(async () => {
        if (busy) return;
        busy = true;
        try {
          const updated = await executionApi.getExecutionById(execId);

          for (let i = prevStepCount; i < updated.stepExecutions.length; i++) {
            const s = updated.stepExecutions[i];
            const icon = s.result === 'SUCCESS' ? '✓' : s.result === 'FAILURE' ? '✗' : '▶';
            addLog(
              `${icon} Шаг ${s.stepIndex} [${s.stepType}] → ${s.result ?? 'выполняется'}`,
              s.result === 'SUCCESS' ? 'success' : s.result === 'FAILURE' ? 'warn' : 'info',
            );
            const isLast = i === updated.stepExecutions.length - 1;
            setExecution({
              ...updated,
              stepExecutions: updated.stepExecutions.slice(0, i + 1),
              currentStepIndex: isLast ? updated.currentStepIndex : s.stepIndex + 1,
            });

            if (!isLast) {
              await new Promise(r => setTimeout(r, STEP_REVEAL_DELAY_MS));
            }
          }
          prevStepCount = updated.stepExecutions.length;
          setExecution(updated);

          if (updated.status === 'COMPLETED' || updated.status === 'ABORTED') {
            stopPoll(); resolve();
          }
        } catch { /* ignore */ } finally {
          busy = false;
        }
      }, 1000);
    });

    const final = await executionApi.getExecutionById(execId).catch(() => null);
    if (final) {
      setExecution(final);
      const ok = final.status === 'COMPLETED';
      addLog(
        `${ok ? '🎉' : '⚠'} Завершено: ${STATUS_LABEL[final.status] ?? final.status}`,
        ok ? 'success' : 'warn',
      );
    }
    setPhase('done');
  }

  const isRunning = phase === 'activating' || phase === 'triggering' || phase === 'waiting' || phase === 'polling';
  const phaseLabel: Record<DemoPhase, string> = {
    idle: 'Готово к запуску',
    activating: 'Активация…',
    triggering: 'Отправка события…',
    waiting: 'Ожидание выполнения…',
    polling: 'Отслеживание шагов…',
    done: 'Завершено',
    error: 'Ошибка',
  };

  return (
    <div className="fade-in-up" style={{ maxWidth: '100%' }}>
      {/* Header */}
      <div style={{ marginBottom: 20, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h2 className="page-title">Демонстрация сценариев</h2>
          <Text style={{ color: c.muted, fontSize: 13 }}>
            Выберите сценарий и нажмите «Запустить» — система выполнит цепочку автоматически
          </Text>
        </div>
        {phase !== 'idle' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {isRunning && <span className="online-dot" />}
            <Text style={{ color: c.muted, fontSize: 12 }}>{phaseLabel[phase]}</Text>
          </div>
        )}
      </div>

      {/* Scenario selector — dropdown with full names */}
      <div style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 13, color: c.muted, marginBottom: 8 }}>
          Выберите сценарий для демонстрации:
        </div>
        <Select
          value={scenarioKey}
          onChange={(val) => { if (!isRunning) { reset(); setScenarioKey(val); } }}
          disabled={isRunning}
          style={{ width: '100%' }}
          size="large"
          options={SCENARIOS.map(s => ({
            value: s.key,
            label: `${s.emoji} ${s.title}`,
          }))}
          optionRender={(option) => {
            const s = SCENARIOS.find(sc => sc.key === option.value)!;
            return (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '3px 0' }}>
                <span style={{ fontSize: 18, lineHeight: 1, flexShrink: 0 }}>{s.emoji}</span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, lineHeight: 1.3 }}>{s.title}</div>
                  <div style={{ fontSize: 11, color: c.muted, marginTop: 2 }}>
                    {s.ucRefs.join(' · ')} · ✈ {s.aircraft} · #{s.flight}
                  </div>
                </div>
              </div>
            );
          }}
        />
      </div>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>

        {/* ── LEFT COLUMN ── */}
        <div style={{ flex: '0 0 320px', minWidth: 280 }}>
          {/* Scenario card */}
          <Card style={{ border: `1px solid ${c.border}`, background: c.bg, marginBottom: 16 }}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span style={{ fontSize: 18, lineHeight: 1 }}>{scenario.emoji}</span>
                <Text strong style={{ color: c.text, fontSize: 15 }}>{scenario.title}</Text>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
                {scenario.ucRefs.map(uc => <Tag key={uc} color="geekblue" style={{ fontSize: 10 }}>{uc}</Tag>)}
                <Tag style={{ fontSize: 10 }}>✈ {scenario.aircraft}</Tag>
                <Tag style={{ fontSize: 10 }}>#{scenario.flight}</Tag>
              </div>
              <Text style={{ color: c.logText, fontSize: 12, lineHeight: 1.6 }}>{scenario.description}</Text>
            </div>

            <Divider style={{ margin: '10px 0', borderColor: c.borderSec }} />

            <Text style={{ color: c.muted, fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.07em', display: 'block', marginBottom: 8 }}>
              Шаги последовательности
            </Text>
            {scenario.steps.map((step, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '10px 0', borderBottom: `1px solid ${c.borderSec}` }}>
                <div style={{
                  width: 22, height: 22, borderRadius: '50%', flexShrink: 0,
                  background: getTypeAccent(step.type, isDark),
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 10, fontWeight: 700, color: '#fff', marginTop: 1,
                }}>{i + 1}</div>
                <Tag
                  color={step.type === 'ACTION' ? 'blue' : step.type === 'EVALUATE' ? 'purple' : 'gold'}
                  style={{ margin: 0, fontSize: 10, flexShrink: 0, marginTop: 1 }}
                >
                  {step.type}
                </Tag>
                <Text style={{
                  color: c.logText, fontSize: 12, lineHeight: 1.5,
                  whiteSpace: 'normal', wordBreak: 'break-word', flex: 1,
                }}>
                  {step.label}
                </Text>
              </div>
            ))}

            {scenario.followUp && (
              <div style={{ marginTop: 10, padding: '6px 10px', borderRadius: 6, background: 'rgba(var(--accent-blue-rgb), 0.08)', border: '1px solid rgba(var(--accent-blue-rgb), 0.20)' }}>
                <Text style={{ color: c.logText, fontSize: 11 }}>
                  <SendOutlined style={{ marginRight: 4, color: 'var(--accent-blue)' }} />
                  Авто-ответ через ~4 сек: {scenario.followUp.label}
                </Text>
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <Button
                type="primary"
                icon={isRunning ? <LoadingOutlined /> : <PlayCircleOutlined />}
                loading={isRunning}
                disabled={isRunning}
                onClick={runDemo}
                style={{
                  flex: 1,
                  height: 42,
                  background: isRunning ? undefined : 'var(--accent-blue)',
                  border: 'none',
                  borderRadius: 11,
                  fontWeight: 600,
                  fontSize: 14,
                }}
              >
                {isRunning ? phaseLabel[phase] : 'Запустить'}
              </Button>
              {phase !== 'idle' && (
                <Button icon={<ReloadOutlined />} onClick={reset} disabled={isRunning} title="Сбросить" />
              )}
            </div>
          </Card>

          {/* Execution result */}
          {execution && (
            <Card style={{ border: `1px solid ${c.border}`, background: c.bg, marginBottom: 16 }}
              styles={{ body: { padding: '12px 16px' } }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <Text strong style={{ color: c.text, fontSize: 13 }}>Выполнение #{execution.id}</Text>
                <Space size={4}>
                  {(execution.status === 'RUNNING' || execution.status === 'WAITING') && <span className="online-dot" />}
                  <Tag color={STATUS_COLOR_TAG[execution.status]}>{STATUS_LABEL[execution.status] ?? execution.status}</Tag>
                </Space>
              </div>

              {/* Mini step progress */}
              <div style={{ display: 'flex', gap: 4, marginBottom: 10, flexWrap: 'wrap' }}>
                {execution.stepExecutions.map(s => (
                  <div key={s.id} style={{
                    width: 28, height: 28, borderRadius: 6, fontSize: 10, fontWeight: 700,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: s.result === 'SUCCESS' ? 'rgba(var(--accent-green-rgb), 0.15)' : s.result === 'FAILURE' ? 'rgba(var(--accent-red-rgb), 0.15)' : 'rgba(var(--accent-blue-rgb), 0.15)',
                    border: `1.5px solid ${s.result === 'SUCCESS' ? 'var(--accent-green)' : s.result === 'FAILURE' ? 'var(--accent-red)' : 'var(--accent-blue)'}`,
                    color: s.result === 'SUCCESS' ? 'var(--accent-green)' : s.result === 'FAILURE' ? 'var(--accent-red)' : 'var(--accent-blue)',
                  }}>
                    {s.result === 'SUCCESS' ? '✓' : s.result === 'FAILURE' ? '✗' : s.stepIndex}
                  </div>
                ))}
                {execution.stepExecutions.length === 0 && (
                  <Text style={{ color: c.dim, fontSize: 11 }}>шаги ещё не выполнялись…</Text>
                )}
              </div>

              <Button
                size="small"
                type="link"
                icon={<RightOutlined />}
                onClick={() => navigate(`/executions/${execution.id}`)}
                style={{ padding: 0, fontSize: 12 }}
              >
                Открыть детали
              </Button>
            </Card>
          )}

          {/* What to watch */}
          <Card style={{ border: `1px solid ${c.border}`, background: c.bg }} styles={{ body: { padding: '12px 16px' } }}>
            <Text style={{ color: c.muted, fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.07em', display: 'block', marginBottom: 10 }}>
              На что смотреть
            </Text>
            {[
              { icon: <NodeExpandOutlined style={{ color: 'var(--accent-blue)' }} />, text: 'Граф обновляется в реальном времени' },
              { icon: <ClockCircleOutlined style={{ color: 'var(--accent-amber)' }} />, text: 'WAIT — жёлтый пульс на текущем шаге' },
              { icon: <CheckCircleOutlined style={{ color: 'var(--accent-green)' }} />, text: 'SUCCESS — зелёный, FAILURE — красный' },
              { icon: <ThunderboltOutlined style={{ color: 'var(--accent-amber)' }} />, text: 'Пройденные рёбра подсвечиваются' },
            ].map((item, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, padding: '6px 0', alignItems: 'flex-start' }}>
                <span style={{ marginTop: 1, fontSize: 13, flexShrink: 0 }}>{item.icon}</span>
                <Text style={{ color: c.logText, fontSize: 12, lineHeight: 1.5 }}>{item.text}</Text>
              </div>
            ))}
            <Divider style={{ borderColor: c.borderSec, margin: '10px 0' }} />
            <div style={{ display: 'flex', gap: 6 }}>
              <Button size="small" onClick={() => navigate('/executions')} style={{ flex: 1 }}>Выполнения</Button>
              <Button size="small" onClick={() => navigate('/messages')} style={{ flex: 1 }}>Сообщения</Button>
            </div>
          </Card>
        </div>

        {/* ── RIGHT COLUMN ── */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {/* Live graph */}
          {sequence && sequence.steps.length > 0 && (
            <Card
              title={
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {isRunning && <span className="online-dot" />}
                  <span style={{ color: c.text, fontSize: 13 }}>
                    {isRunning ? 'Живой граф выполнения' : 'Граф последовательности'}
                  </span>
                </div>
              }
              extra={
                <Button
                  type="text"
                  size="small"
                  onClick={() => setShowGraph(g => !g)}
                  style={{ color: c.muted, fontSize: 12 }}
                >
                  {showGraph ? 'Скрыть' : 'Показать'}
                </Button>
              }
              style={{ border: `1px solid ${isRunning ? 'var(--accent-blue)' : c.border}`, marginBottom: 16, transition: 'border-color 0.4s' }}
            >
              {showGraph && (
                execution ? (
                  <ExecutionFlow
                    steps={sequence.steps}
                    currentStepIndex={execution.currentStepIndex}
                    stepExecutions={execution.stepExecutions}
                  />
                ) : (
                  // Show plain sequence graph before execution starts
                  <ExecutionFlow
                    steps={sequence.steps}
                    currentStepIndex={null}
                    stepExecutions={[]}
                  />
                )
              )}
            </Card>
          )}

          {/* Live log */}
          <Card
            style={{ border: `1px solid ${c.border}`, background: c.bg }}
            styles={{ body: { padding: 0 } }}
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {isRunning && <span className="online-dot" />}
                <Text style={{ color: c.text, fontSize: 13 }}>Журнал выполнения</Text>
                <Text style={{ color: c.dim, fontSize: 11, marginLeft: 4 }}>{log.length} записей</Text>
              </div>
            }
            extra={
              log.length > 0 && (
                <Button type="text" size="small" style={{ color: c.dim, fontSize: 11 }} onClick={() => setLog([])}>
                  Очистить
                </Button>
              )
            }
          >
            <div
              ref={logRef}
              style={{
                height: 240,
                overflowY: 'auto',
                padding: '12px 16px',
                fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                fontSize: 12,
                background: c.bgLow,
                borderBottomLeftRadius: 8,
                borderBottomRightRadius: 8,
              }}
            >
              {log.length === 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 180, gap: 10 }}>
                  <span style={{ fontSize: 36, opacity: 0.25 }}>📋</span>
                  <Text style={{ color: c.dim, fontSize: 13 }}>Нажмите «Запустить» чтобы начать…</Text>
                  <Text style={{ color: c.dim, fontSize: 11 }}>Журнал событий будет обновляться в реальном времени</Text>
                </div>
              ) : (
                log.map((entry, i) => (
                  <div key={i} className="demo-log-line" style={{ marginBottom: 3, display: 'flex', gap: 10 }}>
                    <Text style={{ color: c.dim, fontSize: 10, flexShrink: 0, minWidth: 55 }}>{entry.time}</Text>
                    <Text style={{
                      color: entry.type === 'success' ? 'var(--accent-green)' : entry.type === 'error' ? 'var(--accent-red)' : entry.type === 'warn' ? 'var(--accent-amber)' : c.logText,
                    }}>
                      {entry.text}
                    </Text>
                  </div>
                ))
              )}
              {isRunning && (
                <div style={{ marginTop: 4, color: 'var(--accent-blue)', fontSize: 11 }}>
                  <LoadingOutlined style={{ marginRight: 6 }} />опрашиваем бэкенд…
                </div>
              )}
            </div>
          </Card>
        </div>

      </div>
    </div>
  );
};
