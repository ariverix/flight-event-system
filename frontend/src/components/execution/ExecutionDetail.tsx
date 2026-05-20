import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Descriptions, Tag, notification, Button, Spin, Timeline, Collapse, Typography,
} from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined,
  CheckCircleFilled, CloseCircleFilled, LoadingOutlined,
  ThunderboltOutlined, EyeOutlined, ClockCircleOutlined,
} from '@ant-design/icons';
import { executionApi } from '../../api/executionApi';
import { sequenceApi } from '../../api/sequenceApi';
import { ExecutionInstanceResponse, StepExecutionResponse } from '../../types/execution';
import { SequenceResponse } from '../../types/sequence';
import { ExecutionFlow } from './ExecutionFlow';
import { usePolling } from '../../hooks/usePolling';
import { useTheme } from '../../context/ThemeContext';

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

const StepTimelineItem: React.FC<{ se: StepExecutionResponse; isDark: boolean }> = ({ se, isDark }) => {
  const c = isDark
    ? { bg: '#1c2128', border: '#21262d', text: '#e6edf3', muted: '#848d97' }
    : { bg: '#f6f8fa', border: '#d8dee4', text: '#1f2328', muted: '#636c76' };

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

  const header = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      <Tag color={STEP_TYPE_COLOR[se.stepType] ?? 'blue'} style={{ margin: 0 }}>
        {STEP_ICON[se.stepType] ?? null} {STEP_TYPE_LABEL[se.stepType] ?? se.stepType}
      </Tag>
      <Text style={{ color: c.text, fontWeight: 600 }}>Шаг {se.stepIndex}</Text>
      {se.result && (
        <Tag color={se.result === 'SUCCESS' ? 'success' : 'error'} style={{ margin: 0 }}>
          {se.result === 'SUCCESS' ? 'Успех' : 'Ошибка'}
        </Tag>
      )}
      {se.transitionAction && (
        <Tag color={TRANSITION_COLOR[se.transitionAction] ?? 'default'} style={{ margin: 0 }}>
          {TRANSITION_LABEL[se.transitionAction] ?? se.transitionAction}
          {se.transitionTarget !== null ? ` → ${se.transitionTarget}` : ''}
        </Tag>
      )}
      <Text style={{ color: c.muted, fontSize: 12, marginLeft: 'auto' }}>
        {new Date(se.executedAt).toLocaleString('ru-RU')}
      </Text>
    </div>
  );

  if (!details) {
    return <div style={{ paddingBottom: 4 }}>{header}</div>;
  }

  return (
    <Collapse
      ghost
      size="small"
      style={{ padding: 0 }}
      items={[{
        key: '1',
        label: header,
        children: details,
        style: { padding: 0 },
      }]}
    />
  );
};

const getTimelineDot = (se: StepExecutionResponse): React.ReactNode => {
  if (!se.result) return <LoadingOutlined style={{ color: '#1677ff' }} />;
  if (se.result === 'SUCCESS') return <CheckCircleFilled style={{ color: '#52c41a' }} />;
  return <CloseCircleFilled style={{ color: '#ff4d4f' }} />;
};

export const ExecutionDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const { isDark } = useTheme();
  const c = isDark
    ? { borderSecondary: '#21262d', text: '#e6edf3', muted: '#848d97' }
    : { borderSecondary: '#d8dee4', text: '#1f2328', muted: '#636c76' };

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

  if (loading || !execution || !sequence) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 400 }}>
        <Spin size="large" />
      </div>
    );
  }

  const timelineItems = execution.stepExecutions.map(se => ({
    key: se.id,
    dot: getTimelineDot(se),
    color: se.result === 'SUCCESS' ? 'green' : se.result === 'FAILURE' ? 'red' : 'blue',
    children: <StepTimelineItem se={se} isDark={isDark} />,
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
          <Descriptions.Item label="Начало">
            {new Date(execution.startedAt).toLocaleString('ru-RU')}
          </Descriptions.Item>
          <Descriptions.Item label="Завершение">
            {execution.completedAt
              ? new Date(execution.completedAt).toLocaleString('ru-RU')
              : 'В процессе'}
          </Descriptions.Item>
          <Descriptions.Item label="Контекст" span={2}>
            <pre style={{ fontSize: 11, margin: 0 }}>
              {(() => { try { return JSON.stringify(JSON.parse(execution.contextJson), null, 2); } catch { return execution.contextJson; } })()}
            </pre>
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
