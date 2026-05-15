import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, notification, Select, Popconfirm, Input, Tooltip } from 'antd';
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

const STATUS_LABEL: Record<string, string> = {
  ACTIVE:   'Активна',
  INACTIVE: 'Неактивна',
  DRAFT:    'Черновик',
};

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:   'green',
  INACTIVE: 'orange',
  DRAFT:    'default',
};

export const SequenceList: React.FC = () => {
  const [sequences, setSequences] = useState<SequenceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<SequenceStatus | undefined>();
  const [searchText, setSearchText] = useState('');
  const navigate = useNavigate();

  const loadSequences = async (page = 0, size = 10, status?: SequenceStatus) => {
    setLoading(true);
    try {
      const data = await sequenceApi.getSequences(page, size, status);
      setSequences(data.content);
      setPagination({ current: page + 1, pageSize: size, total: data.totalElements });
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки последовательностей',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSequences(0, pagination.pageSize, statusFilter);
  }, [statusFilter]);

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
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    {
      title: 'Название',
      dataIndex: 'name',
      key: 'name',
      filteredValue: searchText ? [searchText] : null,
      onFilter: (value: any, record: SequenceResponse) =>
        record.name.toLowerCase().includes(value.toLowerCase()),
    },
    {
      title: 'Описание',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      width: 200,
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
      render: (status: SequenceStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status] ?? status}</Tag>
      ),
    },
    {
      title: 'Шаги',
      key: 'steps',
      width: 80,
      render: (_: any, record: SequenceResponse) => record.steps.length,
    },
    {
      title: 'Создан',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleDateString('ru-RU'),
    },
    {
      title: 'Действия',
      key: 'actions',
      width: 310,
      fixed: 'right' as const,
      render: (_: any, record: SequenceResponse) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/sequences/${record.id}`)}
          >
            Просмотр
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/sequences/${record.id}/edit`)}
          >
            Изменить
          </Button>
          {record.status === 'ACTIVE' ? (
            <Button
              type="link"
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleDeactivate(record.id)}
            >
              Деактивировать
            </Button>
          ) : (
            <Button
              type="link"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => handleActivate(record.id)}
              disabled={record.steps.length === 0}
            >
              Активировать
            </Button>
          )}
          <Popconfirm
            title="Удалить последовательность?"
            description="Это действие нельзя отменить."
            onConfirm={() => handleDelete(record.id)}
            okText="Удалить"
            cancelText="Отмена"
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              Удалить
            </Button>
          </Popconfirm>
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
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sequences/new')}>
            Создать
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={sequences}
        loading={loading}
        rowKey="id"
        scroll={{ x: 1000 }}
        pagination={{
          ...pagination,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
        onChange={handleTableChange}
      />
    </div>
  );
};
