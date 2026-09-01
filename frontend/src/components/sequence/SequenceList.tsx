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
import { useEditorI18n } from '../../i18n/useEditorI18n';

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:   'success',
  INACTIVE: 'warning',
  DRAFT:    'default',
};

export const SequenceList: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
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
        message: d.seqLoadError,
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
      notification.success({ message: d.seqDeleteSuccess });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({ message: d.seqDeleteError, description: error.response?.data?.message || error.message });
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await sequenceApi.activateSequence(id);
      notification.success({ message: d.seqActivated });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({ message: d.seqActivateError, description: error.response?.data?.message || error.message });
    }
  };

  const handleDeactivate = async (id: number) => {
    try {
      await sequenceApi.deactivateSequence(id);
      notification.success({ message: d.seqDeactivated });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({ message: d.seqDeactivateError, description: error.response?.data?.message || error.message });
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
      title: d.seqColName,
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
      title: d.seqColDescription,
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
      title: d.colStatus,
      dataIndex: 'status',
      key: 'status',
      width: 112,
      render: (status: SequenceStatus) => (
        <Tag color={STATUS_COLOR[status]}>{d.seqStatuses[status] ?? status}</Tag>
      ),
    },
    {
      title: d.steps,
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
      title: d.seqColCreated,
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
      title: d.seqColActions,
      key: 'actions',
      width: isAdmin ? 280 : 100,
      fixed: 'right' as const,
      onCell: () => ({ style: { background: 'var(--bg-surface)' } }),
      onHeaderCell: () => ({ style: { background: 'var(--bg-surface)' } }),
      render: (_: any, record: SequenceResponse) => (
        <Space size={2} wrap={false}>
          <Button type="text" size="small" icon={<EyeOutlined />}
            onClick={e => { e.stopPropagation(); navigate(`/sequences/${record.id}`); }}>
            {d.seqViewBtn}
          </Button>
          {isAdmin && (
            <Button type="text" size="small" icon={<EditOutlined />}
              onClick={e => { e.stopPropagation(); navigate(`/sequences/${record.id}/edit`); }}>
              {d.editStep}
            </Button>
          )}
          {isAdmin && (record.status === 'ACTIVE' ? (
            <Button type="text" size="small" icon={<PauseCircleOutlined />}
              onClick={e => { e.stopPropagation(); handleDeactivate(record.id); }}>
              {d.seqListDeactivateBtn}
            </Button>
          ) : (
            <Button type="text" size="small" icon={<PlayCircleOutlined />}
              onClick={e => { e.stopPropagation(); handleActivate(record.id); }}
              disabled={(record.steps?.length ?? 0) === 0}>
              {d.seqActivateBtn}
            </Button>
          ))}
          {isAdmin && (
            <Popconfirm
              title={d.seqDeleteConfirmTitle}
              description={d.seqDeleteConfirmDesc}
              onConfirm={() => handleDelete(record.id)}
              okText={d.deleteStep} cancelText={d.usersCancelBtn}
              okButtonProps={{ danger: true }}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />}
                onClick={e => e.stopPropagation()}>
                {d.deleteStep}
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
        <h2 className="page-title">{d.seqListTitle}</h2>
        <Space wrap>
          <Input.Search
            placeholder={d.seqSearchPlaceholder}
            value={searchText}
            onChange={e => setSearchText(e.target.value)}
            onSearch={setSearchText}
            style={{ width: 210 }}
            allowClear
          />
          <Select
            placeholder={d.execStatusFilterPlaceholder}
            style={{ width: 160 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="ACTIVE">{d.seqStatuses.ACTIVE}</Select.Option>
            <Select.Option value="INACTIVE">{d.seqStatuses.INACTIVE}</Select.Option>
            <Select.Option value="DRAFT">{d.seqStatuses.DRAFT}</Select.Option>
          </Select>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sequences/new')}>
              {d.seqCreateBtn}
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
                <div style={{ color: 'var(--text-3)', fontSize: 14 }}>{d.seqEmptyText}</div>
                <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                  {searchText ? d.seqEmptySearchHint : d.seqEmptyCreateHint}
                </div>
              </div>
            ),
          }}
          pagination={{
            ...pagination,
            total: filtered.length < sequences.length ? filtered.length : pagination.total,
            showTotal: (total, range) => `${range[0]}–${range[1]} ${d.paginationOf} ${total}`,
          }}
          onChange={handleTableChange}
        />
      )}
    </div>
  );
};
