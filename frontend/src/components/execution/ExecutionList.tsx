import React, { useState, useEffect } from 'react';
import { Table, Tag, notification, Select, Input, Space, Button } from 'antd';
import { EyeOutlined, ReloadOutlined } from '@ant-design/icons';
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

export const ExecutionList: React.FC = () => {
  const [executions, setExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<ExecutionStatus | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const navigate = useNavigate();

  const loadExecutions = async (
    page = 0,
    size = 10,
    status?: ExecutionStatus,
    aircraftId?: string,
  ) => {
    setLoading(true);
    try {
      const data = await executionApi.getExecutions(page, size, status, aircraftId);
      setExecutions(data.content);
      setPagination({ current: page + 1, pageSize: size, total: data.totalElements });
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки выполнений',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadExecutions(0, pagination.pageSize, statusFilter, aircraftIdFilter);
  }, [statusFilter, aircraftIdFilter]);

  const handleTableChange = (pg: any) => {
    loadExecutions(pg.current - 1, pg.pageSize, statusFilter, aircraftIdFilter);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 65, fixed: 'left' as const },
    { title: 'Последовательность', dataIndex: 'sequenceName', key: 'sequenceName', minWidth: 160 },
    { title: 'Идент. ВС', dataIndex: 'aircraftId', key: 'aircraftId', width: 110 },
    {
      title: 'Номер рейса',
      dataIndex: 'flightNumber',
      key: 'flightNumber',
      width: 110,
      render: (v: string | null) => v || '—',
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 130,
      render: (status: ExecutionStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status] ?? status}</Tag>
      ),
    },
    {
      title: 'Текущий шаг',
      dataIndex: 'currentStepIndex',
      key: 'currentStepIndex',
      width: 110,
      render: (step: number | null) => step !== null ? `Шаг ${step}` : '—',
    },
    {
      title: 'Начало',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 150,
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
    {
      title: 'Завершение',
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 150,
      render: (date: string | null) => date ? new Date(date).toLocaleString('ru-RU') : 'В процессе',
    },
    {
      title: '',
      key: 'actions',
      width: 90,
      fixed: 'right' as const,
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
        <Space>
          <Input
            placeholder="Фильтр по идент. ВС"
            style={{ width: 200 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder="Фильтр по статусу"
            style={{ width: 180 }}
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
            onClick={() =>
              loadExecutions(pagination.current - 1, pagination.pageSize, statusFilter, aircraftIdFilter)
            }
          >
            Обновить
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={executions}
        loading={loading}
        rowKey="id"
        scroll={{ x: 900 }}
        pagination={{
          ...pagination,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
        onChange={handleTableChange}
      />
    </div>
  );
};
