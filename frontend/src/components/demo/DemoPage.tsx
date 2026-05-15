import React, { useState, useRef, useEffect } from 'react';
import { Card, Tag, Typography, Button, Select, Divider, Steps } from 'antd';
import {
  PlayCircleOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ThunderboltOutlined,
  SendOutlined,
  RightOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { messageApi } from '../../api/messageApi';
import { sequenceApi } from '../../api/sequenceApi';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse } from '../../types/execution';
import { useTheme } from '../../context/ThemeContext';

const { Title, Text } = Typography;

type DemoPhase = 'idle' | 'activating' | 'triggering' | 'waiting' | 'done' | 'error';

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
}

const SCENARIOS: Scenario[] = [
  {
    key: 'weather',
    title: 'Распределение метеоинформации',
    seqName: 'Распределение метеоинформации',
    aircraft: 'SU9876',
    flight: 'AFL123',
    ucRefs: ['UC-06'],
    description: 'Мгновенное выполнение — все 3 ACTION-шага завершаются за секунды. Отлично показывает автоматическую цепочку.',
    trigger: {
      type: 'message',
      payload: {
        messageType: 'GROUND',
        templateName: 'WEATHER_UPDATE',
        aircraftId: 'SU9876',
        flightNumber: 'AFL123',
        metadataJson: '{"temperature":-5,"wind":"270/10kt","visibility":"10km"}',
      },
    },
    steps: [
      { label: 'Переслать метеосводку экипажу', type: 'ACTION' },
      { label: 'Уведомить диспетчерскую', type: 'ACTION' },
      { label: 'Зафиксировать WEATHER_ADVISORY_SENT', type: 'ACTION' },
    ],
  },
  {
    key: 'delay',
    title: 'Уведомление о задержке рейса',
    seqName: 'Уведомление о задержке рейса',
    aircraft: 'SU9876',
    flight: 'AFL123',
    ucRefs: ['UC-06', 'UC-07'],
    description: 'Демонстрирует EVALUATE-шаг: при первом запуске алерта нет → цепочка выполняется. При повторном — алерт уже есть → END.',
    trigger: {
      type: 'message',
      payload: {
        messageType: 'GROUND',
        templateName: 'DELAY_NOTICE',
        aircraftId: 'SU9876',
        flightNumber: 'AFL123',
        metadataJson: '{"delayMinutes":45,"reason":"ATC_RESTRICTION"}',
      },
    },
    steps: [
      { label: 'EVALUATE: алерт FLIGHT_DELAYED уже активен?', type: 'EVALUATE' },
      { label: 'Подтвердить получение уведомления', type: 'ACTION' },
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
    description: 'WAIT-шаг с тайм-аутом 30 сек. Без ответа экипажа — автоматически запрашивает контакт и поднимает алерт.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'SU9876', flightNumber: 'AFL123', newStage: 'ON' },
    },
    steps: [
      { label: 'WAIT 30 сек: LANDING_REPORT от экипажа', type: 'WAIT' },
      { label: 'Запросить контакт CONTACT_REQUEST', type: 'ACTION' },
      { label: 'Поднять алерт NO_LANDING_CONTACT', type: 'ACTION' },
    ],
  },
  {
    key: 'preflight',
    title: 'Предполётная подготовка',
    seqName: 'Предполётная подготовка',
    aircraft: 'SU1234',
    flight: 'AFL456',
    ucRefs: ['UC-06', 'UC-08'],
    description: 'Автостарт на FlightStage=INIT. Чеклист экипажу → WAIT подтверждения → при ответе COMPLETED, при тайм-ауте → алерт CRITICAL.',
    trigger: {
      type: 'stage',
      payload: { aircraftId: 'SU1234', flightNumber: 'AFL456', newStage: 'INIT' },
    },
    followUp: {
      delayMs: 4000,
      label: 'Экипаж подтверждает чеклист (PREFLIGHT_COMPLETE)',
      payload: {
        messageType: 'DOWNLINK',
        templateName: 'PREFLIGHT_COMPLETE',
        aircraftId: 'SU1234',
        flightNumber: 'AFL456',
        metadataJson: '{"pilot":"Иванов","copilot":"Петров"}',
      },
    },
    steps: [
      { label: 'Отправить предполётный чеклист', type: 'ACTION' },
      { label: 'WAIT 30 сек: PREFLIGHT_COMPLETE', type: 'WAIT' },
      { label: 'Алерт: PREFLIGHT_TIMEOUT (если нет ответа)', type: 'ACTION' },
    ],
  },
];

const STEP_COLOR: Record<string, string> = {
  ACTION: '#1677ff',
  EVALUATE: '#faad14',
  WAIT: '#722ed1',
};

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Выполняется',
  WAITING: 'Ожидание',
  COMPLETED: 'Завершено',
  ABORTED: 'Прервано',
};

function timestamp() {
  return new Date().toLocaleTimeString('ru-RU');
}

export const DemoPage: React.FC = () => {
  const navigate = useNavigate();
  const { isDark } = useTheme();
  const [scenarioKey, setScenarioKey] = useState<string>('weather');
  const [phase, setPhase] = useState<DemoPhase>('idle');
  const [log, setLog] = useState<LogEntry[]>([]);
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [followUpSent, setFollowUpSent] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const logRef = useRef<HTMLDivElement>(null);

  const c = isDark
    ? {
        border: '#30363d',
        borderSecondary: '#21262d',
        text: '#e6edf3',
        textMuted: '#848d97',
        textDimmer: '#484f58',
        bgContainer: '#161b22',
        bgLayout: '#0d1117',
        logText: '#8b949e',
      }
    : {
        border: '#d0d7de',
        borderSecondary: '#d8dee4',
        text: '#1f2328',
        textMuted: '#636c76',
        textDimmer: '#9da3ab',
        bgContainer: '#ffffff',
        bgLayout: '#f6f8fa',
        logText: '#57606a',
      };

  const scenario = SCENARIOS.find(s => s.key === scenarioKey)!;

  function addLog(text: string, type: LogEntry['type'] = 'info') {
    setLog(prev => [...prev, { time: timestamp(), text, type }]);
  }

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [log]);

  function stopPoll() {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }

  function reset() {
    stopPoll();
    setPhase('idle');
    setLog([]);
    setExecution(null);
    setFollowUpSent(false);
  }

  async function runDemo() {
    reset();
    const sc = SCENARIOS.find(s => s.key === scenarioKey)!;
    setPhase('activating');

    addLog(`Ищем последовательность: «${sc.seqName}»...`);
    let seqId: number | null = null;
    try {
      const page = await sequenceApi.getSequences(0, 50);
      const found = page.content.find(s => s.name === sc.seqName);
      if (!found) {
        addLog(`Последовательность не найдена. Убедитесь что V11-миграция применена.`, 'error');
        setPhase('error');
        return;
      }
      seqId = found.id;
      addLog(`Найдена: ID=${found.id}, статус=${found.status}`, 'success');

      if (found.status !== 'ACTIVE') {
        addLog(`Активируем последовательность...`);
        await sequenceApi.activateSequence(found.id);
        addLog(`Статус → ACTIVE`, 'success');
      } else {
        addLog(`Уже активна`, 'success');
      }
    } catch (e: any) {
      addLog(`Ошибка: ${e.response?.data?.message || e.message}`, 'error');
      setPhase('error');
      return;
    }

    // Запоминаем максимальный ID выполнения ДО отправки события
    let maxExistingId = 0;
    try {
      const existing = await executionApi.getExecutions(0, 1, undefined, sc.aircraft);
      if (existing.content.length > 0) maxExistingId = existing.content[0].id;
    } catch {/* ignore */}

    setPhase('triggering');
    addLog(`Отправляем триггерное событие...`);
    try {
      if (sc.trigger.type === 'stage') {
        await messageApi.changeFlightStage(sc.trigger.payload);
        addLog(`FlightStage → ${sc.trigger.payload.newStage} (ВС: ${sc.aircraft})`, 'success');
      } else {
        await messageApi.sendMessage(sc.trigger.payload);
        addLog(`Сообщение ${sc.trigger.payload.templateName} отправлено (ВС: ${sc.aircraft})`, 'success');
      }
    } catch (e: any) {
      addLog(`Ошибка отправки: ${e.response?.data?.message || e.message}`, 'error');
      setPhase('error');
      return;
    }

    setPhase('waiting');
    addLog(`Ждём запуска выполнения...`);
    let foundExec: ExecutionInstanceResponse | null = null;
    let attempts = 0;

    await new Promise<void>((resolve) => {
      pollRef.current = setInterval(async () => {
        attempts++;
        try {
          const page = await executionApi.getExecutions(0, 20, undefined, sc.aircraft);
          // Ищем новое выполнение (ID > maxExistingId) для нужной последовательности
          const match = page.content.find(
            e => e.sequenceId === seqId && e.id > maxExistingId
          );
          if (match) {
            foundExec = match;
            stopPoll();
            resolve();
          } else if (attempts > 20) {
            stopPoll();
            resolve();
          }
        } catch {
          /* ignore */
        }
      }, 1000);
    });

    if (!foundExec) {
      addLog(`Выполнение не появилось за 20 сек. Проверьте логи бэкенда.`, 'warn');
      setPhase('error');
      return;
    }

    setExecution(foundExec);
    addLog(`Выполнение запущено! ID=${(foundExec as ExecutionInstanceResponse).id}`, 'success');

    if (sc.followUp && !followUpSent) {
      setTimeout(async () => {
        try {
          addLog(`→ ${sc.followUp!.label}`);
          await messageApi.sendMessage(sc.followUp!.payload);
          addLog(`Отправлено: ${sc.followUp!.payload.templateName}`, 'success');
          setFollowUpSent(true);
        } catch {/* ignore */}
      }, sc.followUp.delayMs);
    }

    const execId = (foundExec as ExecutionInstanceResponse).id;
    let prevStepCount = 0;

    await new Promise<void>((resolve) => {
      pollRef.current = setInterval(async () => {
        try {
          const updated = await executionApi.getExecutionById(execId);
          setExecution(updated);

          if (updated.stepExecutions.length > prevStepCount) {
            for (let i = prevStepCount; i < updated.stepExecutions.length; i++) {
              const s = updated.stepExecutions[i];
              const icon = s.result === 'SUCCESS' ? '✓' : s.result === 'FAILURE' ? '✗' : '…';
              addLog(
                `Шаг ${s.stepIndex} [${s.stepType}] ${icon} ${s.result ?? 'выполняется'}`,
                s.result === 'SUCCESS' ? 'success' : s.result === 'FAILURE' ? 'warn' : 'info'
              );
            }
            prevStepCount = updated.stepExecutions.length;
          }

          if (updated.status === 'COMPLETED' || updated.status === 'ABORTED') {
            stopPoll();
            resolve();
          }
        } catch {/* ignore */}
      }, 1200);
    });

    const final = await executionApi.getExecutionById(execId).catch(() => null);
    if (final) {
      setExecution(final);
      addLog(
        `Выполнение завершено: ${STATUS_LABEL[final.status] ?? final.status}`,
        final.status === 'COMPLETED' ? 'success' : 'warn'
      );
    }
    setPhase('done');
  }

  const isRunning = phase === 'activating' || phase === 'triggering' || phase === 'waiting';

  return (
    <div className="fade-in-up" style={{ maxWidth: 1100 }}>
      <div style={{ marginBottom: 24 }}>
        <Title level={4} style={{ margin: 0, color: c.text }}>Демонстрация сценариев</Title>
        <Text style={{ color: c.textMuted }}>Выберите сценарий и нажмите «Запустить» — система выполнит всё автоматически</Text>
      </div>

      <div style={{ display: 'flex', gap: 20, alignItems: 'flex-start', flexWrap: 'wrap' }}>

        {/* ── LEFT: controls ── */}
        <div style={{ flex: '1 1 340px', minWidth: 300 }}>
          <Card
            style={{ border: `1px solid ${c.border}`, background: c.bgContainer, maxHeight: 300, overflowY: 'auto' }}
            styles={{ body: { padding: '12px 16px' } }}
          >
            <div style={{ marginBottom: 10 }}>
              <Text style={{ color: c.textMuted, fontSize: 12, display: 'block', marginBottom: 4 }}>
                СЦЕНАРИЙ
              </Text>
              <Select
                value={scenarioKey}
                onChange={v => { reset(); setScenarioKey(v); }}
                disabled={isRunning}
                style={{ width: '100%' }}
                options={SCENARIOS.map(s => ({ value: s.key, label: s.title }))}
              />
            </div>

            {/* Scenario info */}
            <div style={{
              padding: '8px 12px',
              background: c.bgLayout,
              borderRadius: 8,
              border: `1px solid ${c.borderSecondary}`,
              marginBottom: 10,
            }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
                {scenario.ucRefs.map(uc => (
                  <Tag key={uc} color="geekblue" style={{ fontSize: 11 }}>{uc}</Tag>
                ))}
                <Tag style={{ fontSize: 11 }}>Борт: {scenario.aircraft}</Tag>
                <Tag style={{ fontSize: 11 }}>Рейс: {scenario.flight}</Tag>
              </div>
              <Text style={{ color: c.logText, fontSize: 12, lineHeight: 1.6 }}>
                {scenario.description}
              </Text>
            </div>

            {/* Steps preview */}
            <div style={{ marginBottom: 10 }}>
              <Text style={{ color: c.textMuted, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', display: 'block', marginBottom: 8 }}>
                Шаги
              </Text>
              {scenario.steps.map((step, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                  <div style={{
                    width: 20, height: 20, borderRadius: '50%', flexShrink: 0,
                    background: STEP_COLOR[step.type],
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 10, fontWeight: 700, color: '#fff',
                  }}>{i + 1}</div>
                  <Tag color={step.type === 'ACTION' ? 'blue' : step.type === 'EVALUATE' ? 'gold' : 'purple'}
                    style={{ margin: 0, fontSize: 10 }}>
                    {step.type}
                  </Tag>
                  <Text style={{ color: c.logText, fontSize: 12 }}>{step.label}</Text>
                </div>
              ))}
              {scenario.followUp && (
                <div style={{ marginTop: 8, padding: '6px 10px', borderRadius: 6, background: 'rgba(22,119,255,0.08)', border: '1px solid rgba(22,119,255,0.15)' }}>
                  <Text style={{ color: c.logText, fontSize: 11 }}>
                    <SendOutlined style={{ marginRight: 4, color: '#1677ff' }} />
                    Авто-ответ через ~4 сек: {scenario.followUp.label}
                  </Text>
                </div>
              )}
            </div>

            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                type="primary"
                icon={isRunning ? <LoadingOutlined /> : <PlayCircleOutlined />}
                loading={isRunning}
                disabled={isRunning}
                onClick={runDemo}
              >
                {isRunning ? 'Выполняется...' : 'Запустить демонстрацию'}
              </Button>
              {phase !== 'idle' && (
                <Button icon={<ReloadOutlined />} onClick={reset} title="Сбросить" />
              )}
            </div>
          </Card>

          {/* Execution result card */}
          {execution && (
            <Card style={{ border: `1px solid ${c.border}`, background: c.bgContainer, marginTop: 16 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <Text strong style={{ color: c.text }}>Выполнение #{execution.id}</Text>
                <Tag color={execution.status === 'COMPLETED' ? 'green' : execution.status === 'WAITING' ? 'gold' : execution.status === 'RUNNING' ? 'blue' : 'red'}>
                  {STATUS_LABEL[execution.status] ?? execution.status}
                </Tag>
              </div>
              <div
                onClick={() => navigate(`/executions/${execution.id}`)}
                style={{ cursor: 'pointer', color: '#1677ff', fontSize: 12, marginBottom: 12 }}
              >
                Открыть детали <RightOutlined />
              </div>
              {execution.stepExecutions.length > 0 && (
                <Steps
                  direction="vertical"
                  size="small"
                  current={execution.stepExecutions.length - 1}
                  style={{ fontSize: 12 }}
                  items={execution.stepExecutions.map(s => ({
                    title: <Text style={{ color: c.text, fontSize: 12 }}>Шаг {s.stepIndex} · {s.stepType}</Text>,
                    description: <Text style={{ color: c.textMuted, fontSize: 11 }}>{s.result ?? '…'}</Text>,
                    status: s.result === 'SUCCESS' ? 'finish' : s.result === 'FAILURE' ? 'error' : 'process',
                    icon: s.result === 'SUCCESS' ? <CheckCircleOutlined /> : s.result === null ? <LoadingOutlined /> : undefined,
                  }))}
                />
              )}
            </Card>
          )}
        </div>

        {/* ── RIGHT: live log ── */}
        <div style={{ flex: '1 1 380px', minWidth: 320 }}>
          <Card
            style={{ border: `1px solid ${c.border}`, background: c.bgContainer }}
            styles={{ body: { padding: 0 } }}
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {isRunning && <span className="online-dot" />}
                <Text style={{ color: c.text, fontSize: 13 }}>Лог выполнения</Text>
              </div>
            }
          >
            <div
              ref={logRef}
              style={{
                height: 380,
                overflowY: 'auto',
                padding: '12px 16px',
                fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                fontSize: 12,
                background: c.bgLayout,
                borderBottomLeftRadius: 8,
                borderBottomRightRadius: 8,
              }}
            >
              {log.length === 0 && (
                <Text style={{ color: c.textDimmer }}>Нажмите «Запустить демонстрацию» чтобы начать...</Text>
              )}
              {log.map((entry, i) => (
                <div key={i} style={{ marginBottom: 4, display: 'flex', gap: 10 }}>
                  <Text style={{ color: c.textDimmer, fontSize: 11, flexShrink: 0 }}>{entry.time}</Text>
                  <Text style={{
                    color: entry.type === 'success' ? '#3fb950'
                      : entry.type === 'error' ? '#f85149'
                      : entry.type === 'warn' ? '#d29922'
                      : c.logText,
                  }}>
                    {entry.type === 'success' ? '✓ ' : entry.type === 'error' ? '✗ ' : entry.type === 'warn' ? '⚠ ' : '  '}
                    {entry.text}
                  </Text>
                </div>
              ))}
              {isRunning && (
                <div style={{ marginTop: 6, color: '#1677ff', fontSize: 11 }}>
                  <LoadingOutlined style={{ marginRight: 6 }} />опрашиваем бэкенд...
                </div>
              )}
            </div>
          </Card>

          {/* What to watch */}
          <Card style={{ border: `1px solid ${c.border}`, background: c.bgContainer, marginTop: 16 }}>
            <Text style={{ color: c.textMuted, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', display: 'block', marginBottom: 10 }}>
              На что смотреть
            </Text>
            {[
              { icon: <ThunderboltOutlined style={{ color: '#faad14' }} />, text: 'Раздел «Выполнения» — появится новая запись' },
              { icon: <ClockCircleOutlined style={{ color: '#722ed1' }} />, text: 'Статус WAITING означает что шаг ждёт сообщения' },
              { icon: <CheckCircleOutlined style={{ color: '#52c41a' }} />, text: 'Нажмите на выполнение — увидите каждый шаг с результатом' },
              { icon: <SendOutlined style={{ color: '#1677ff' }} />, text: 'Журнал сообщений — все отправленные события' },
            ].map((item, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'flex-start' }}>
                <span style={{ marginTop: 1, fontSize: 13, flexShrink: 0 }}>{item.icon}</span>
                <Text style={{ color: c.logText, fontSize: 12 }}>{item.text}</Text>
              </div>
            ))}
            <Divider style={{ borderColor: c.borderSecondary, margin: '10px 0' }} />
            <div style={{ display: 'flex', gap: 8 }}>
              <Button size="small" onClick={() => navigate('/executions')} style={{ flex: 1 }}>
                Выполнения
              </Button>
              <Button size="small" onClick={() => navigate('/messages')} style={{ flex: 1 }}>
                Сообщения
              </Button>
              <Button size="small" onClick={() => navigate('/sequences')} style={{ flex: 1 }}>
                Последовательности
              </Button>
            </div>
          </Card>
        </div>
      </div>

      {/* ── Demo order guide ── */}
      <Card style={{ border: `1px solid ${c.border}`, background: c.bgContainer, marginTop: 20 }}>
        <Text style={{ color: c.textMuted, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', display: 'block', marginBottom: 14 }}>
          Рекомендуемый порядок демонстрации на защите
        </Text>
        <div style={{ display: 'flex', gap: 0, overflowX: 'auto' }}>
          {[
            { n: '1', label: 'Вход', desc: 'admin/admin', color: '#1677ff' },
            { n: '2', label: 'Панель управления', desc: 'Статистика', color: '#1677ff' },
            { n: '3', label: 'Последовательности', desc: 'UC-01..UC-05', color: '#722ed1' },
            { n: '4', label: 'Метеоинформация', desc: 'Быстрый показ', color: '#faad14' },
            { n: '5', label: 'Задержка рейса', desc: 'EVALUATE-ветка', color: '#faad14' },
            { n: '6', label: 'Предполётная', desc: 'WAIT + авто-ответ', color: '#00c853' },
            { n: '7', label: 'Контроль связи', desc: 'Тайм-аут 30 сек', color: '#ff4d4f' },
            { n: '8', label: 'Журнал аудита', desc: 'ADMIN-права', color: '#848d97' },
          ].map((step, i, arr) => (
            <React.Fragment key={i}>
              <div style={{ textAlign: 'center', minWidth: 90, padding: '0 4px' }}>
                <div style={{
                  width: 32, height: 32, borderRadius: '50%',
                  background: `${step.color}22`,
                  border: `2px solid ${step.color}66`,
                  color: step.color,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: 700, fontSize: 13, margin: '0 auto 6px',
                }}>
                  {step.n}
                </div>
                <div style={{ fontSize: 12, fontWeight: 600, color: c.text }}>{step.label}</div>
                <div style={{ fontSize: 11, color: c.textDimmer, marginTop: 2 }}>{step.desc}</div>
              </div>
              {i < arr.length - 1 && (
                <div style={{ display: 'flex', alignItems: 'center', padding: '0 2px', paddingBottom: 20 }}>
                  <RightOutlined style={{ color: c.border, fontSize: 10 }} />
                </div>
              )}
            </React.Fragment>
          ))}
        </div>
      </Card>
    </div>
  );
};
