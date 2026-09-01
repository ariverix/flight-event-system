import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Select, Input, Space, Button, Skeleton } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import {
  EyeOutlined, ReloadOutlined,
  CheckCircleFilled, CloseCircleFilled, SyncOutlined, ClockCircleOutlined,
  InboxOutlined,
} from '@ant-design/icons';
import { usePolling } from '../../hooks/usePolling';
import { useNavigate } from 'react-router-dom';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse, ExecutionStatus } from '../../types/execution';
import { useEditorI18n } from '../../i18n/useEditorI18n';

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

const ExecutionHeader: React.FC<{ executions: ExecutionInstanceResponse[]; total: number }> = ({ executions, total }) => {
  const d = useEditorI18n();
  const running   = executions.filter(e => e.status === 'RUNNING').length;
  const completed = executions.filter(e => e.status === 'COMPLETED').length;
  const aborted   = executions.filter(e => e.status === 'ABORTED').length;

  const chips = [
    { label: d.execTotalLabel,          value: total,     color: 'var(--accent-indigo)' },
    { label: d.instanceStatuses.RUNNING,   value: running,   color: 'var(--accent-blue)' },
    { label: d.instanceStatuses.COMPLETED, value: completed, color: 'var(--accent-green)' },
    { label: d.instanceStatuses.ABORTED,   value: aborted,   color: 'var(--accent-red)' },
  ];

  return (
    <div style={{ display: 'flex', gap: 10, marginBottom: 14, flexWrap: 'wrap' }}>
      {chips.map(s => (
        <div
          key={s.label}
          style={{
            padding: '8px 16px',
            borderRadius: 10,
            background: `${s.color}12`,
            border: `1px solid ${s.color}28`,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <span style={{ fontSize: 22, fontWeight: 700, color: s.color, lineHeight: 1 }}>
            {s.value}
          </span>
          <span style={{ fontSize: 12, color: 'var(--text-2)' }}>{s.label}</span>
        </div>
      ))}
    </div>
  );
};

export const ExecutionList: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
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
        message: d.execLoadError,
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
      title: d.auditEntityLabels.SEQUENCE,
      dataIndex: 'sequenceName',
      key: 'sequenceName',
      ellipsis: { showTitle: false },
      render: (v: string) => <span title={v}>{v}</span>,
    },
    {
      title: d.execColAircraftFlight,
      key: 'aircraft',
      width: 140,
      render: (_: any, r: ExecutionInstanceResponse) => (
        <span style={{ fontSize: 13 }}>
          ✈ {r.aircraftId}{r.flightNumber ? ` · ${r.flightNumber}` : ''}
        </span>
      ),
    },
    {
      title: d.colStatus,
      dataIndex: 'status',
      key: 'status',
      width: 150,
      render: (status: ExecutionStatus) => (
        <span style={{ display: 'inline-flex', alignItems: 'center' }}>
          <StatusIcon status={status} />
          <Tag color={STATUS_COLOR[status]} style={{ margin: 0 }}>
            {d.instanceStatuses[status] ?? status}
          </Tag>
        </span>
      ),
    },
    {
      title: d.eventStep,
      dataIndex: 'currentStepIndex',
      key: 'currentStepIndex',
      width: 70,
      render: (step: number | null) => step !== null
        ? <Tag style={{ fontVariantNumeric: 'tabular-nums' }}>#{step}</Tag>
        : <span style={{ color: 'var(--text-3)' }}>—</span>,
    },
    {
      title: d.colStarted,
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
      title: d.execColCompleted,
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 135,
      render: (date: string | null) => date ? (
        <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
          {new Date(date).toLocaleString('ru-RU')}
        </span>
      ) : (
        <span style={{ color: 'var(--text-3)', fontSize: 12 }}>{d.execInProgress}</span>
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
          {d.detailsBtn}
        </Button>
      ),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">{d.execListTitle}</h2>
        <Space wrap>
          <Input
            placeholder={d.execAircraftFilterPlaceholder}
            style={{ width: 180 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder={d.execStatusFilterPlaceholder}
            style={{ width: 170 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="WAITING">{d.instanceStatuses.WAITING}</Select.Option>
            <Select.Option value="RUNNING">{d.instanceStatuses.RUNNING}</Select.Option>
            <Select.Option value="COMPLETED">{d.instanceStatuses.COMPLETED}</Select.Option>
            <Select.Option value="ABORTED">{d.instanceStatuses.ABORTED}</Select.Option>
          </Select>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadExecutions(pagination.current - 1, pagination.pageSize, statusFilter, aircraftIdFilter)}
          >
            {d.refreshBtn}
          </Button>
        </Space>
      </div>

      {hasActive && (
        <div style={{ marginBottom: 10, display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-2)' }}>
          <span className="online-dot" />
          {d.execAutoRefreshNote}
        </div>
      )}

      <ExecutionHeader executions={executions} total={pagination.total} />

      {loading && executions.length === 0 ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : (
        <Table
          columns={columns}
          dataSource={executions}
          loading={loading}
          rowKey="id"
          scroll={{ x: 'max-content' }}
          locale={{
            emptyText: (
              <div style={{ padding: '40px 0', textAlign: 'center' }}>
                <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
                <div style={{ color: 'var(--text-3)', fontSize: 14 }}>{d.execEmptyText}</div>
                <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                  {d.execEmptyHint}
                </div>
              </div>
            ),
          }}
          rowClassName={(record) =>
            record.status === 'RUNNING' ? 'execution-row-running'
            : record.status === 'WAITING' ? 'execution-row-waiting'
            : ''
          }
          pagination={{
            ...pagination,
            showTotal: (total, range) => `${range[0]}–${range[1]} ${d.paginationOf} ${total}`,
          }}
          onChange={(pg) => loadExecutions(pg.current! - 1, pg.pageSize!, statusFilter, aircraftIdFilter)}
        />
      )}
    </div>
  );
};
