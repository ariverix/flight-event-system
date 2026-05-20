import React, { useState, useEffect, useCallback } from 'react';
import { Table, Button, Space, Tag, notification, Select, Popconfirm, Input, Tooltip, Skeleton } from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { sequenceApi } from '../../api/sequenceApi';
import { SequenceResponse, SequenceStatus } from '../../types/sequence';
import { useAuth } from '../../hooks/useAuth';

const STATUS_LABEL: Record<string, string> = {
  ACTIVE:   'Активна',
  INACTIVE: 'Неактивна',
  DRAFT:    'Черновик',
};

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:   'success',
  INACTIVE: 'warning',
  DRAFT:    'default',
};

export const SequenceList: React.FC = () => {
  const [sequences, setSequences] = useState<SequenceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<SequenceStatus | undefined>();
  const [searchText, setSearchText] = useState('');
  const navigate = useNavigate();
  const { isAdmin } = useAuth();

  const loadSequences = useCallback(async (page = 0, size = 10, status?: SequenceStatus) => {
    setLoading(true);
    try {
      const data = await sequenceApi.getSequences(page, size, status);
      setSequences(data.content);
      setPagination(prev => ({ ...prev, current: page + 1, pageSize: size, total: data.totalElements }));
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки последовательностей',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSequences(0, 10, statusFilter);
  }, [statusFilter, loadSequences]);

  const handleTableChange = (pg: any) => {
    loadSequences(pg.current - 1, pg.pageSize, statusFilter);
  };

  const handleDelete = async (id: number) => {
    try {
      await sequenceApi.deleteSequence(id);
      notification.success({ message: 'Последовательность удалена' });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка удаления',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await sequenceApi.activateSequence(id);
      notification.success({ message: 'Последовательность активирована' });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка активации',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleDeactivate = async (id: number) => {
    try {
      await sequenceApi.deactivateSequence(id);
      notification.success({ message: 'Последовательность деактивирована' });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка деактивации',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60, fixed: 'left' as const },
    {
      title: 'Название',
      dataIndex: 'name',
      key: 'name',
      width: 220,
      ellipsis: true,
      filteredValue: searchText ? [searchText] : null,
      onFilter: (value: any, record: SequenceResponse) =>
        record.name.toLowerCase().includes((value as string).toLowerCase()),
      render: (text: string) => (
        <Tooltip title={text} placement="topLeft">
          <span style={{ fontWeight: 500 }}>{text}</span>
        </Tooltip>
      ),
    },
    {
      title: 'Описание',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      width: 220,
      render: (text: string) => (
        <Tooltip title={text} placement="topLeft">
          <span>{text}</span>
        </Tooltip>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (status: SequenceStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status] ?? status}</Tag>
      ),
    },
    {
      title: 'Шаги',
      key: 'steps',
      width: 70,
      render: (_: any, record: SequenceResponse) => (
        <Tag>{record.steps.length}</Tag>
      ),
    },
    {
      title: 'Создан',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 120,
      render: (date: string) => new Date(date).toLocaleDateString('ru-RU'),
    },
    {
      title: 'Действия',
      key: 'actions',
      width: isAdmin ? 300 : 100,
      fixed: 'right' as const,
      render: (_: any, record: SequenceResponse) => (
        <Space size={2} className="row-actions">
          <Button
            type="text"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/sequences/${record.id}`)}
          >
            Просмотр
          </Button>
          {isAdmin && (
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => navigate(`/sequences/${record.id}/edit`)}
            >
              Изменить
            </Button>
          )}
          {isAdmin && (record.status === 'ACTIVE' ? (
            <Button
              type="text"
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleDeactivate(record.id)}
            >
              Деактив.
            </Button>
          ) : (
            <Button
              type="text"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => handleActivate(record.id)}
              disabled={record.steps.length === 0}
            >
              Активировать
            </Button>
          ))}
          {isAdmin && (
            <Popconfirm
              title="Удалить последовательность?"
              description="Это действие нельзя отменить."
              onConfirm={() => handleDelete(record.id)}
              okText="Удалить"
              cancelText="Отмена"
              okButtonProps={{ danger: true }}
            >
              <Button type="text" size="small" danger icon={<DeleteOutlined />}>
                Удалить
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Последовательности событий</h2>
        <Space>
          <Input.Search
            placeholder="Поиск по названию"
            onSearch={setSearchText}
            style={{ width: 220 }}
            allowClear
          />
          <Select
            placeholder="Фильтр по статусу"
            style={{ width: 170 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="ACTIVE">Активна</Select.Option>
            <Select.Option value="INACTIVE">Неактивна</Select.Option>
            <Select.Option value="DRAFT">Черновик</Select.Option>
          </Select>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sequences/new')}>
              Создать
            </Button>
          )}
        </Space>
      </div>

      {loading && sequences.length === 0 ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : (
        <Table
          columns={columns}
          dataSource={sequences}
          loading={loading}
          rowKey="id"
          scroll={{ x: 1100 }}
          rowClassName="sequence-table-row"
          pagination={{
            ...pagination,
            showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
          }}
          onChange={handleTableChange}
        />
      )}
    </div>
  );
};
