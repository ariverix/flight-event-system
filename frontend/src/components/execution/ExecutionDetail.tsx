import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Table, Tag, notification, Button, Spin } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { executionApi } from '../../api/executionApi';
import { sequenceApi } from '../../api/sequenceApi';
import { ExecutionInstanceResponse, StepResult } from '../../types/execution';
import { SequenceResponse } from '../../types/sequence';
import { ExecutionFlow } from './ExecutionFlow';
import { usePolling } from '../../hooks/usePolling';
import { useTheme } from '../../context/ThemeContext';

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

export const ExecutionDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const { isDark } = useTheme();
  const c = isDark
    ? { borderSecondary: '#21262d', text: '#e6edf3' }
    : { borderSecondary: '#d8dee4', text: '#1f2328' };

  const loadExecution = async () => {
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
  };

  useEffect(() => { loadExecution(); }, [id]);

  usePolling(
    loadExecution,
    5000,
    execution?.status === 'RUNNING' || execution?.status === 'WAITING',
  );

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

  const stepColumns = [
    { title: 'Шаг', dataIndex: 'stepIndex', key: 'stepIndex', width: 60 },
    {
      title: 'Тип',
      dataIndex: 'stepType',
      key: 'stepType',
      width: 100,
      render: (type: string) => (
        <Tag color={STEP_TYPE_COLOR[type] ?? 'blue'}>{STEP_TYPE_LABEL[type] ?? type}</Tag>
      ),
    },
    {
      title: 'Результат',
      dataIndex: 'result',
      key: 'result',
      width: 110,
      render: (result: StepResult | null) =>
        result ? (
          <Tag color={result === 'SUCCESS' ? 'success' : 'error'}>
            {result === 'SUCCESS' ? 'Успех' : 'Ошибка'}
          </Tag>
        ) : (
          <Tag color="processing">В процессе</Tag>
        ),
    },
    {
      title: 'Решение',
      dataIndex: 'transitionAction',
      key: 'transitionAction',
      width: 120,
      render: (action: string | null, record: any) => {
        if (!action) return '—';
        const label = TRANSITION_LABEL[action] ?? action;
        const color = TRANSITION_COLOR[action] ?? 'default';
        return (
          <Tag color={color}>
            {label}{record.transitionTarget != null ? ` → ${record.transitionTarget}` : ''}
          </Tag>
        );
      },
    },
    {
      title: 'Время',
      dataIndex: 'executedAt',
      key: 'executedAt',
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
    {
      title: 'Детали',
      dataIndex: 'detailsJson',
      key: 'detailsJson',
      render: (details: string | null) => {
        if (!details) return '—';
        try {
          return (
            <pre style={{ fontSize: '11px', margin: 0 }}>
              {JSON.stringify(JSON.parse(details), null, 2)}
            </pre>
          );
        } catch {
          return details;
        }
      },
    },
  ];

  if (loading || !execution || !sequence) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '400px' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="fade-in-up">
      <div className="page-header" style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/executions')}>
          Назад к выполнениям
        </Button>
        <Button icon={<ReloadOutlined />} onClick={loadExecution}>
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
          <Descriptions.Item label="Номер рейса">
            {execution.flightNumber || '—'}
          </Descriptions.Item>
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
            <pre style={{ fontSize: '11px', margin: 0 }}>
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
        <Table
          columns={stepColumns}
          dataSource={execution.stepExecutions}
          rowKey="id"
          pagination={false}
        />
      </Card>
    </div>
  );
};
