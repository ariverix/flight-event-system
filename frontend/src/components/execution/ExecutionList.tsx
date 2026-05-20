import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, notification, Select, Input, Space, Button, Skeleton } from 'antd';
import {
  EyeOutlined, ReloadOutlined,
  CheckCircleFilled, CloseCircleFilled, SyncOutlined, ClockCircleOutlined,
} from '@ant-design/icons';
import { usePolling } from '../../hooks/usePolling';
import { useNavigate } from 'react-router-dom';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse, ExecutionStatus } from '../../types/execution';

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

const StatusIcon: React.FC<{ status: ExecutionStatus }> = ({ status }) => {
  if (status === 'COMPLETED') return <CheckCircleFilled style={{ color: 'var(--accent-green)', marginRight: 5 }} />;
  if (status === 'ABORTED')   return <CloseCircleFilled  style={{ color: 'var(--accent-red)',   marginRight: 5 }} />;
  if (status === 'RUNNING')   return <SyncOutlined spin  style={{ color: 'var(--accent-blue)',  marginRight: 5 }} />;
  if (status === 'WAITING')   return <ClockCircleOutlined style={{ color: 'var(--accent-amber)', marginRight: 5 }} />;
  return null;
};

export const ExecutionList: React.FC = () => {
  const [executions, setExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<ExecutionStatus | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const [hasActive, setHasActive] = useState(false);
  const navigate = useNavigate();

  const loadExecutions = useCallback(async (
    page = 0, size = 10, status?: ExecutionStatus, aircraftId?: string,
  ) => {
    setLoading(true);
    try {
      const data = await executionApi.getExecutions(page, size, status, aircraftId);
      setExecutions(data.content);
      setPagination(prev => ({ ...prev, current: page + 1, pageSize: size, total: data.totalElements }));
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки выполнений',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadExecutions(0, 10, statusFilter, aircraftIdFilter);
  }, [statusFilter, aircraftIdFilter, loadExecutions]);

  useEffect(() => {
    setHasActive(executions.some(e => e.status === 'RUNNING' || e.status === 'WAITING'));
  }, [executions]);

  usePolling(() => loadExecutions(0, 10, statusFilter, aircraftIdFilter), 4000, hasActive && !statusFilter);

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 55,
    },
    {
      title: 'Последовательность',
      dataIndex: 'sequenceName',
      key: 'sequenceName',
      ellipsis: { showTitle: false },
      render: (v: string) => <span title={v}>{v}</span>,
    },
    {
      title: 'Борт / Рейс',
      key: 'aircraft',
      width: 140,
      render: (_: any, r: ExecutionInstanceResponse) => (
        <span style={{ fontSize: 13 }}>
          ✈ {r.aircraftId}{r.flightNumber ? ` · ${r.flightNumber}` : ''}
        </span>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 150,
      render: (status: ExecutionStatus) => (
        <span style={{ display: 'inline-flex', alignItems: 'center' }}>
          <StatusIcon status={status} />
          <Tag color={STATUS_COLOR[status]} style={{ margin: 0 }}>
            {STATUS_LABEL[status] ?? status}
          </Tag>
        </span>
      ),
    },
    {
      title: 'Шаг',
      dataIndex: 'currentStepIndex',
      key: 'currentStepIndex',
      width: 70,
      render: (step: number | null) => step !== null
        ? <Tag style={{ fontVariantNumeric: 'tabular-nums' }}>#{step}</Tag>
        : <span style={{ color: 'var(--text-3)' }}>—</span>,
    },
    {
      title: 'Начало',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 135,
      render: (date: string) => (
        <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
          {new Date(date).toLocaleString('ru-RU')}
        </span>
      ),
    },
    {
      title: 'Завершение',
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 135,
      render: (date: string | null) => date ? (
        <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
          {new Date(date).toLocaleString('ru-RU')}
        </span>
      ) : (
        <span style={{ color: 'var(--text-3)', fontSize: 12 }}>В процессе</span>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 85,
      render: (_: any, record: ExecutionInstanceResponse) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => navigate(`/executions/${record.id}`)}
        >
          Детали
        </Button>
      ),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Экземпляры выполнений</h2>
        <Space wrap>
          <Input
            placeholder="Фильтр по борту"
            style={{ width: 180 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder="Фильтр по статусу"
            style={{ width: 170 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="WAITING">Ожидание</Select.Option>
            <Select.Option value="RUNNING">Выполняется</Select.Option>
            <Select.Option value="COMPLETED">Завершено</Select.Option>
            <Select.Option value="ABORTED">Прервано</Select.Option>
          </Select>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadExecutions(pagination.current - 1, pagination.pageSize, statusFilter, aircraftIdFilter)}
          >
            Обновить
          </Button>
        </Space>
      </div>

      {hasActive && (
        <div style={{ marginBottom: 10, display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-2)' }}>
          <span className="online-dot" />
          Автообновление — есть активные выполнения
        </div>
      )}

      {loading && executions.length === 0 ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : (
        <Table
          columns={columns}
          dataSource={executions}
          loading={loading}
          rowKey="id"
          scroll={{ x: 'max-content' }}
          rowClassName={(record) =>
            record.status === 'RUNNING' ? 'execution-row-running'
            : record.status === 'WAITING' ? 'execution-row-waiting'
            : ''
          }
          pagination={{
            ...pagination,
            showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
          }}
          onChange={(pg) => loadExecutions(pg.current! - 1, pg.pageSize!, statusFilter, aircraftIdFilter)}
        />
      )}
    </div>
  );
};
