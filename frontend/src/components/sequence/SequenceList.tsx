import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Table, Button, Space, Tag, Select, Popconfirm, Input, Skeleton, Tooltip } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import { InboxOutlined } from '@ant-design/icons';
import {
  PlusOutlined, EditOutlined, DeleteOutlined,
  PlayCircleOutlined, PauseCircleOutlined, EyeOutlined,
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
  const notification = useNotification();
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
      notification.error({ message: 'Ошибка удаления', description: error.response?.data?.message || error.message });
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await sequenceApi.activateSequence(id);
      notification.success({ message: 'Последовательность активирована' });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({ message: 'Ошибка активации', description: error.response?.data?.message || error.message });
    }
  };

  const handleDeactivate = async (id: number) => {
    try {
      await sequenceApi.deactivateSequence(id);
      notification.success({ message: 'Последовательность деактивирована' });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({ message: 'Ошибка деактивации', description: error.response?.data?.message || error.message });
    }
  };

  // Live search filter — memoised to avoid re-computing on every render
  const filtered = useMemo(
    () => searchText
      ? sequences.filter(s => s.name.toLowerCase().includes(searchText.toLowerCase()))
      : sequences,
    [sequences, searchText],
  );

  const columns = [
    {
      title: 'Название',
      dataIndex: 'name',
      key: 'name',
      width: 200,
      ellipsis: { showTitle: false },
      render: (text: string) => (
        <Tooltip placement="topLeft" title={text}>
          <strong>{text}</strong>
        </Tooltip>
      ),
    },
    {
      title: 'Описание',
      dataIndex: 'description',
      key: 'description',
      width: '40%',
      ellipsis: { showTitle: false },
      render: (text: string) => (
        <Tooltip placement="topLeft" title={text}>
          <span style={{ color: 'var(--text-2)', fontSize: 13 }}>{text || '—'}</span>
        </Tooltip>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 112,
      render: (status: SequenceStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status] ?? status}</Tag>
      ),
    },
    {
      title: 'Шаги',
      key: 'steps',
      width: 80,
      align: 'center' as const,
      render: (_: any, record: SequenceResponse) => (
        <Tag style={{ minWidth: 32, textAlign: 'center' }}>
          {record.steps?.length ?? 0}
        </Tag>
      ),
    },
    {
      title: 'Создан',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 130,
      render: (date: string | null) => date ? (
        <span style={{ whiteSpace: 'nowrap', fontSize: 13 }}>
          {new Date(date).toLocaleDateString('ru-RU')}
        </span>
      ) : <span style={{ color: 'var(--text-3)' }}>—</span>,
    },
    {
      title: 'Действия',
      key: 'actions',
      width: isAdmin ? 280 : 100,
      fixed: 'right' as const,
      onCell: () => ({ style: { background: 'var(--bg-surface)' } }),
      onHeaderCell: () => ({ style: { background: 'var(--bg-surface)' } }),
      render: (_: any, record: SequenceResponse) => (
        <Space size={2} wrap={false}>
          <Button type="text" size="small" icon={<EyeOutlined />}
            onClick={e => { e.stopPropagation(); navigate(`/sequences/${record.id}`); }}>
            Просмотр
          </Button>
          {isAdmin && (
            <Button type="text" size="small" icon={<EditOutlined />}
              onClick={e => { e.stopPropagation(); navigate(`/sequences/${record.id}/edit`); }}>
              Изменить
            </Button>
          )}
          {isAdmin && (record.status === 'ACTIVE' ? (
            <Button type="text" size="small" icon={<PauseCircleOutlined />}
              onClick={e => { e.stopPropagation(); handleDeactivate(record.id); }}>
              Деактив.
            </Button>
          ) : (
            <Button type="text" size="small" icon={<PlayCircleOutlined />}
              onClick={e => { e.stopPropagation(); handleActivate(record.id); }}
              disabled={(record.steps?.length ?? 0) === 0}>
              Активировать
            </Button>
          ))}
          {isAdmin && (
            <Popconfirm
              title="Удалить последовательность?"
              description="Это действие нельзя отменить."
              onConfirm={() => handleDelete(record.id)}
              okText="Удалить" cancelText="Отмена"
              okButtonProps={{ danger: true }}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />}
                onClick={e => e.stopPropagation()}>
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
        <Space wrap>
          <Input.Search
            placeholder="Поиск по названию"
            value={searchText}
            onChange={e => setSearchText(e.target.value)}
            onSearch={setSearchText}
            style={{ width: 210 }}
            allowClear
          />
          <Select
            placeholder="Фильтр по статусу"
            style={{ width: 160 }}
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
          dataSource={filtered}
          loading={loading}
          rowKey="id"
          scroll={{ x: 1200 }}
          rowClassName={(record: SequenceResponse) =>
            `sequence-table-row seq-${record.status.toLowerCase()}`
          }
          locale={{
            emptyText: (
              <div style={{ padding: '40px 0', textAlign: 'center' }}>
                <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
                <div style={{ color: 'var(--text-3)', fontSize: 14 }}>Последовательностей нет</div>
                <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                  {searchText ? 'Ничего не найдено по запросу' : 'Создайте первую последовательность событий'}
                </div>
              </div>
            ),
          }}
          pagination={{
            ...pagination,
            total: filtered.length < sequences.length ? filtered.length : pagination.total,
            showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
          }}
          onChange={handleTableChange}
        />
      )}
    </div>
  );
};
