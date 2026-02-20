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

export const ExecutionDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [execution, setExecution] = useState<ExecutionInstanceResponse | null>(null);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [loading, setLoading] = useState(false);

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
        message: 'Failed to load execution details',
        description: error.response?.data?.message || error.message,
      });
      navigate('/executions');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadExecution();
  }, [id]);

  usePolling(
    loadExecution,
    5000,
    execution?.status === 'RUNNING' || execution?.status === 'WAITING'
  );

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'RUNNING':
        return 'processing';
      case 'COMPLETED':
        return 'success';
      case 'ABORTED':
        return 'error';
      case 'WAITING':
        return 'warning';
      default:
        return 'default';
    }
  };

  const getResultColor = (result: StepResult | null) => {
    switch (result) {
      case 'SUCCESS':
        return 'success';
      case 'FAILURE':
        return 'error';
      default:
        return 'default';
    }
  };

  const stepColumns = [
    {
      title: 'Step Index',
      dataIndex: 'stepIndex',
      key: 'stepIndex',
    },
    {
      title: 'Step Type',
      dataIndex: 'stepType',
      key: 'stepType',
      render: (type: string) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: 'Result',
      dataIndex: 'result',
      key: 'result',
      render: (result: StepResult | null) => (
        result ? <Tag color={getResultColor(result)}>{result}</Tag> : <Tag>IN PROGRESS</Tag>
      ),
    },
    {
      title: 'Executed At',
      dataIndex: 'executedAt',
      key: 'executedAt',
      render: (date: string) => new Date(date).toLocaleString(),
    },
    {
      title: 'Completed At',
      dataIndex: 'completedAt',
      key: 'completedAt',
      render: (date: string | null) => date ? new Date(date).toLocaleString() : 'In Progress',
    },
    {
      title: 'Details',
      dataIndex: 'detailsJson',
      key: 'detailsJson',
      render: (details: string | null) => {
        if (!details) return 'N/A';
        try {
          const parsed = JSON.parse(details);
          return <pre style={{ fontSize: '11px', margin: 0 }}>{JSON.stringify(parsed, null, 2)}</pre>;
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
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/executions')}>
          Back to Executions
        </Button>
        <Button icon={<ReloadOutlined />} onClick={loadExecution}>
          Refresh
        </Button>
      </div>

      <Card title="Execution Details" style={{ marginBottom: 16 }}>
        <Descriptions bordered column={2}>
          <Descriptions.Item label="Execution ID">{execution.id}</Descriptions.Item>
          <Descriptions.Item label="Status">
            <Tag color={getStatusColor(execution.status)}>{execution.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Sequence">{execution.sequenceName}</Descriptions.Item>
          <Descriptions.Item label="Aircraft ID">{execution.aircraftId}</Descriptions.Item>
          <Descriptions.Item label="Flight Number">
            {execution.flightNumber || 'N/A'}
          </Descriptions.Item>
          <Descriptions.Item label="Current Step">
            {execution.currentStepIndex !== null ? `Step ${execution.currentStepIndex}` : 'N/A'}
          </Descriptions.Item>
          <Descriptions.Item label="Started At">
            {new Date(execution.startedAt).toLocaleString()}
          </Descriptions.Item>
          <Descriptions.Item label="Completed At">
            {execution.completedAt ? new Date(execution.completedAt).toLocaleString() : 'In Progress'}
          </Descriptions.Item>
          <Descriptions.Item label="Context" span={2}>
            <pre style={{ fontSize: '11px' }}>
              {JSON.stringify(JSON.parse(execution.contextJson), null, 2)}
            </pre>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="Visual Progress" style={{ marginBottom: 16 }}>
        <ExecutionFlow
          steps={sequence.steps}
          currentStepIndex={execution.currentStepIndex}
          stepExecutions={execution.stepExecutions}
        />
      </Card>

      <Card title="Step Execution History">
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
