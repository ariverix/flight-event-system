import React, { useState, useEffect } from 'react';
import { Table, Tag, notification, Select, Input, Space, Button } from 'antd';
import { EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse, ExecutionStatus } from '../../types/execution';

export const ExecutionList: React.FC = () => {
  const [executions, setExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<ExecutionStatus | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const navigate = useNavigate();

  const loadExecutions = async (
    page: number = 0,
    size: number = 10,
    status?: ExecutionStatus,
    aircraftId?: string
  ) => {
    setLoading(true);
    try {
      const data = await executionApi.getExecutions(page, size, status, aircraftId);
      setExecutions(data.content);
      setPagination({
        current: page + 1,
        pageSize: size,
        total: data.totalElements,
      });
    } catch (error: any) {
      notification.error({
        message: 'Failed to load executions',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadExecutions(0, pagination.pageSize, statusFilter, aircraftIdFilter);
  }, [statusFilter, aircraftIdFilter]);

  const handleTableChange = (newPagination: any) => {
    loadExecutions(newPagination.current - 1, newPagination.pageSize, statusFilter, aircraftIdFilter);
  };

  const getStatusColor = (status: ExecutionStatus) => {
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

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80,
    },
    {
      title: 'Sequence',
      dataIndex: 'sequenceName',
      key: 'sequenceName',
    },
    {
      title: 'Aircraft ID',
      dataIndex: 'aircraftId',
      key: 'aircraftId',
    },
    {
      title: 'Flight Number',
      dataIndex: 'flightNumber',
      key: 'flightNumber',
      render: (flightNumber: string | null) => flightNumber || 'N/A',
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: ExecutionStatus) => (
        <Tag color={getStatusColor(status)}>{status}</Tag>
      ),
    },
    {
      title: 'Current Step',
      dataIndex: 'currentStepIndex',
      key: 'currentStepIndex',
      render: (step: number | null) => step !== null ? `Step ${step}` : 'N/A',
    },
    {
      title: 'Started At',
      dataIndex: 'startedAt',
      key: 'startedAt',
      render: (date: string) => new Date(date).toLocaleString(),
    },
    {
      title: 'Completed At',
      dataIndex: 'completedAt',
      key: 'completedAt',
      render: (date: string | null) => date ? new Date(date).toLocaleString() : 'In Progress',
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: any, record: ExecutionInstanceResponse) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => navigate(`/executions/${record.id}`)}
        >
          View Details
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Execution Instances</h2>
        <Space>
          <Input
            placeholder="Filter by Aircraft ID"
            style={{ width: 200 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder="Filter by status"
            style={{ width: 150 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="WAITING">WAITING</Select.Option>
            <Select.Option value="RUNNING">RUNNING</Select.Option>
            <Select.Option value="COMPLETED">COMPLETED</Select.Option>
            <Select.Option value="ABORTED">ABORTED</Select.Option>
          </Select>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadExecutions(pagination.current - 1, pagination.pageSize, statusFilter, aircraftIdFilter)}
          >
            Refresh
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={executions}
        loading={loading}
        rowKey="id"
        pagination={pagination}
        onChange={handleTableChange}
      />
    </div>
  );
};
