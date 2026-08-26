import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, Button, Spin, Timeline, Typography, Progress,
} from 'antd';
import { useNotification } from '../../hooks/useNotification';
import {
  ArrowLeftOutlined, ReloadOutlined,
  CheckCircleFilled, CloseCircleFilled, LoadingOutlined,
  ThunderboltOutlined, EyeOutlined, ClockCircleOutlined, FieldTimeOutlined,
  DownOutlined, UpOutlined,
} from '@ant-design/icons';
import { executionApi } from '../../api/executionApi';
import { sequenceApi } from '../../api/sequenceApi';
import { ExecutionInstanceResponse, StepExecutionResponse } from '../../types/execution';
import { SequenceResponse } from '../../types/sequence';
import { ExecutionFlow } from './ExecutionFlow';
import { usePolling } from '../../hooks/usePolling';
import { useTheme } from '../../context/ThemeContext';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { getExecutionStatusColor } from '../../utils/executionStatusColors';

const { Text } = Typography;

const STATUS_LABEL: Record<string, string> = {
  RUNNING:   'Выполняется',
  COMPLETED: 'Завершено',
  ABORTED:   'Прервано',
  WAITING:   'Ожидание',
};

const STATUS_COLOR: Record<string, string> = {
  RUNNING:   'processing',
  COMPLETED: 'success',
  ABORTED:   'error',
  WAITING:   'warning',
};

const STEP_TYPE_COLOR: Record<string, string> = {
  ACTION:   'blue',
  EVALUATE: 'gold',
  WAIT:     'purple',
};

const STEP_TYPE_LABEL: Record<string, string> = {
  ACTION:   'Действие',
  EVALUATE: 'Оценка',
  WAIT:     'Ожидание',
};

const TRANSITION_LABEL: Record<string, string> = {
  CONTINUE: 'Продолжить',
  GOTO:     'Перейти',
  END:      'Завершить',
  ABORT:    'Прервать',
};

const TRANSITION_COLOR: Record<string, string> = {
  CONTINUE: 'blue',
  GOTO:     'purple',
  END:      'green',
  ABORT:    'red',
};

const STEP_ICON: Record<string, React.ReactNode> = {
  ACTION:   <ThunderboltOutlined />,
  EVALUATE: <EyeOutlined />,
  WAIT:     <ClockCircleOutlined />,
};

const StepTimelineItem: React.FC<{ se: StepExecutionResponse; prevSe?: StepExecutionResponse; isDark: boolean }> = ({ se, prevSe, isDark }) => {
  const [open, setOpen] = useState(false);
  const d = useEditorI18n();
  const c = isDark
    ? { bg: '#262626', border: 'rgba(255,255,255,0.09)', text: '#f5f5f7', muted: 'rgba(255,255,255,0.55)' }
    : { bg: '#f5f5f7', border: 'rgba(0,0,0,0.08)', text: '#1d1d1f', muted: '#6e6e73' };

  let details: React.ReactNode = null;
  if (se.detailsJson) {
    try {
      const parsed = JSON.stringify(JSON.parse(se.detailsJson), null, 2);
      details = (
        <pre style={{ fontSize: 11, margin: 0, color: c.muted, background: c.bg, padding: '8px', borderRadius: 6, border: `1px solid ${c.border}` }}>
          {parsed}
        </pre>
      );
    } catch {
      details = <Text style={{ color: c.muted, fontSize: 12 }}>{se.detailsJson}</Text>;
    }
  }

  const badgeStyle: React.CSSProperties = {
    margin: 0, display: 'inline-flex', alignItems: 'center', gap: 4,
    height: 24, lineHeight: '22px', boxSizing: 'border-box',
  };

  const header = (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Tag color={STEP_TYPE_COLOR[se.stepType] ?? 'blue'} style={badgeStyle}>
          {STEP_ICON[se.stepType] ?? null} {STEP_TYPE_LABEL[se.stepType] ?? se.stepType}
        </Tag>
        <Text style={{ color: c.text, fontWeight: 600 }}>Шаг {se.stepIndex}</Text>
        {se.result && (
          <Tag color={se.result === 'SUCCESS' ? 'success' : 'error'} style={badgeStyle}>
            {se.result === 'SUCCESS' ? 'Успех' : 'Ошибка'}
          </Tag>
        )}
        {se.transitionAction && (
          <Tag color={TRANSITION_COLOR[se.transitionAction] ?? 'default'} style={badgeStyle}>
            {TRANSITION_LABEL[se.transitionAction] ?? se.transitionAction}
            {se.transitionTarget !== null ? ` → ${se.transitionTarget}` : ''}
          </Tag>
        )}
        {details && (
          <Button
            type="text"
            size="small"
            icon={open ? <UpOutlined /> : <DownOutlined />}
            onClick={() => setOpen(o => !o)}
            aria-label={open ? d.collapseDetails : d.expandDetails}
            style={{ height: 24, padding: '0 6px' }}
          />
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
        {prevSe && (() => {
          const dur = Math.round((new Date(se.executedAt).getTime() - new Date(prevSe.executedAt).getTime()) / 1000);
          if (dur > 0) return (
            <span style={{ fontSize: 11, color: c.muted, background: 'rgba(255,255,255,0.06)',
              borderRadius: 10, padding: '1px 7px', display: 'inline-flex', alignItems: 'center', height: 20, boxSizing: 'border-box' }}>
              {dur >= 60 ? `${Math.floor(dur/60)}м ${dur%60}с` : `${dur}с`}
            </span>
          );
        })()}
        <Text style={{ color: c.muted, fontSize: 12, whiteSpace: 'nowrap' }}>
          {new Date(se.executedAt).toLocaleString('ru-RU')}
        </Text>
      </div>
    </div>
  );

  const rowStyle: React.CSSProperties = {
    paddingBottom: 12,
    marginBottom: 12,
    borderBottom: `1px solid ${c.border}`,
  };

  return (
    <div style={rowStyle}>
      {header}
      {details && open && <div style={{ marginTop: 8 }}>{details}</div>}
    </div>
  );
};

const getTimelineDot = (se: StepExecutionResponse): React.ReactNode => {
  if (!se.result) return <LoadingOutlined style={{ color: '#0a84ff' }} />;
  if (se.result === 'SUCCESS') return <CheckCircleFilled style={{ color: '#30d158' }} />;
  return <CloseCircleFilled style={{ color: '#ff453a' }} />;
};

export const ExecutionDetail: React.FC = () => {
  const notification = useNotification();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const elapsedRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const { isDark } = useTheme();
  const c = isDark
    ? { borderSecondary: 'rgba(255,255,255,0.08)', text: 'var(--text-1)', muted: 'var(--text-2)' }
    : { borderSecondary: 'rgba(0,0,0,0.08)', text: 'var(--text-1)', muted: 'var(--text-2)' };

  const fmtElapsed = (s: number) => {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) return `${h}ч ${m}м ${sec}с`;
    if (m > 0) return `${m}м ${sec}с`;
    return `${sec}с`;
  };

  const loadExecution = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const execData = await executionApi.getExecutionById(parseInt(id));
      setExecution(execData);
      const seqData = await sequenceApi.getSequenceById(execData.sequenceId);
      setSequence(seqData);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки деталей выполнения',
        description: error.response?.data?.message || error.message,
      });
      navigate('/executions');
    } finally {
      setLoading(false);
    }
  }, [id, navigate]);

  useEffect(() => { loadExecution(); }, [loadExecution]);

  const isActive = execution?.status === 'RUNNING' || execution?.status === 'WAITING';
  usePolling(loadExecution, 5000, isActive);

  // Elapsed timer
  useEffect(() => {
    if (elapsedRef.current) clearInterval(elapsedRef.current);
    if (!execution) return;
    const startMs = new Date(execution.startedAt).getTime();
    const endMs   = execution.completedAt ? new Date(execution.completedAt).getTime() : null;
    if (endMs) { setElapsed(Math.floor((endMs - startMs) / 1000)); return; }
    setElapsed(Math.floor((Date.now() - startMs) / 1000));
    elapsedRef.current = setInterval(() => {
      setElapsed(Math.floor((Date.now() - startMs) / 1000));
    }, 1000);
    return () => { if (elapsedRef.current) clearInterval(elapsedRef.current); };
  }, [execution?.startedAt, execution?.completedAt]);

  if (loading || !execution || !sequence) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 400 }}>
        <Spin size="large" />
      </div>
    );
  }

  const totalSteps = sequence.steps.length;
  const doneSteps  = execution.stepExecutions.filter(s => s.result != null).length;
  const progressPct = totalSteps > 0 ? Math.round((doneSteps / totalSteps) * 100) : 0;
  const progressStatus = execution.status === 'ABORTED' ? 'exception'
    : execution.status === 'COMPLETED' ? 'success'
    : 'active';

  const timelineItems = execution.stepExecutions.map((se, idx) => ({
    key: se.id,
    dot: getTimelineDot(se),
    color: se.result === 'SUCCESS' ? 'green' : se.result === 'FAILURE' ? 'red' : 'blue',
    children: (
      <StepTimelineItem
        se={se}
        prevSe={idx > 0 ? execution.stepExecutions[idx - 1] : undefined}
        isDark={isDark}
      />
    ),
  }));

  return (
    <div className="fade-in-up">
      <div className="page-header" style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/executions')}>
          Назад к выполнениям
        </Button>
        <Button icon={<ReloadOutlined />} onClick={loadExecution} loading={loading}>
          Обновить
        </Button>
      </div>

      <Card
        title={<span style={{ color: c.text }}>Детали выполнения</span>}
        style={{ marginBottom: 16, borderColor: c.borderSecondary }}
      >
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label="ID выполнения">{execution.id}</Descriptions.Item>
          <Descriptions.Item label="Статус">
            <Tag color={STATUS_COLOR[execution.status]}>
              {STATUS_LABEL[execution.status] ?? execution.status}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Последовательность">{execution.sequenceName}</Descriptions.Item>
          <Descriptions.Item label="Идентификатор ВС">{execution.aircraftId}</Descriptions.Item>
          <Descriptions.Item label="Номер рейса">{execution.flightNumber || '—'}</Descriptions.Item>
          <Descriptions.Item label="Текущий шаг">
            {execution.currentStepIndex !== null ? `Шаг ${execution.currentStepIndex}` : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="Прогресс" span={2}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ flex: 1 }}>
                <Progress
                  percent={progressPct}
                  status={progressStatus}
                  size="small"
                  strokeColor={getExecutionStatusColor(
                    execution.status === 'ABORTED' || execution.status === 'COMPLETED'
                      ? execution.status
                      : 'RUNNING',
                    isDark,
                  )}
                />
              </div>
              <span style={{ fontSize: 12, color: c.muted, flexShrink: 0 }}>
                {doneSteps}/{totalSteps} шагов
              </span>
              {elapsed > 0 && (
                <span className="elapsed-badge">
                  <FieldTimeOutlined />
                  {fmtElapsed(elapsed)}
                </span>
              )}
            </div>
          </Descriptions.Item>
          <Descriptions.Item label="Начало">
            {new Date(execution.startedAt).toLocaleString('ru-RU')}
          </Descriptions.Item>
          <Descriptions.Item label="Завершение">
            {execution.completedAt
              ? new Date(execution.completedAt).toLocaleString('ru-RU')
              : 'В процессе'}
          </Descriptions.Item>
          <Descriptions.Item label="Контекст" span={2}>
            {(() => {
              try {
                const parsed = JSON.parse(execution.contextJson);
                if (Object.keys(parsed).length === 0) return <span style={{ color: 'var(--text-3)' }}>—</span>;
                return (
                  <pre style={{ fontSize: 11, margin: 0, maxHeight: 160, overflow: 'auto',
                    background: 'rgba(255,255,255,0.03)', borderRadius: 6, padding: '8px 10px',
                    border: '1px solid rgba(255,255,255,0.06)' }}>
                    {JSON.stringify(parsed, null, 2)}
                  </pre>
                );
              } catch {
                return execution.contextJson
                  ? <span style={{ fontSize: 12 }}>{execution.contextJson}</span>
                  : <span style={{ color: 'var(--text-3)' }}>—</span>;
              }
            })()}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card
        title={<span style={{ color: c.text }}>Визуальный прогресс</span>}
        style={{ marginBottom: 16, borderColor: c.borderSecondary }}
      >
        <ExecutionFlow
          steps={sequence.steps}
          currentStepIndex={execution.currentStepIndex}
          stepExecutions={execution.stepExecutions}
        />
      </Card>

      <Card
        title={<span style={{ color: c.text }}>История выполнения шагов</span>}
        style={{ borderColor: c.borderSecondary }}
      >
        {timelineItems.length === 0 ? (
          <Text style={{ color: c.muted }}>Шаги ещё не выполнялись</Text>
        ) : (
          <Timeline items={timelineItems} style={{ marginTop: 16 }} />
        )}
      </Card>
    </div>
  );
};
