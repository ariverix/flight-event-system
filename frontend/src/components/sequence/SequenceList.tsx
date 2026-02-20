import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Tag, notification, Select, Popconfirm, Input } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, PlayCircleOutlined, PauseCircleOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { sequenceApi } from '../../api/sequenceApi';
import { SequenceResponse, SequenceStatus } from '../../types/sequence';

export const SequenceList: React.FC = () => {
  const [sequences, setSequences] = useState<SequenceResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [statusFilter, setStatusFilter] = useState<SequenceStatus | undefined>();
  const [searchText, setSearchText] = useState('');
  const navigate = useNavigate();

  const loadSequences = async (page: number = 0, size: number = 10, status?: SequenceStatus) => {
    setLoading(true);
    try {
      const data = await sequenceApi.getSequences(page, size, status);
      setSequences(data.content);
      setPagination({
        current: page + 1,
        pageSize: size,
        total: data.totalElements,
      });
    } catch (error: any) {
      notification.error({
        message: 'Failed to load sequences',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSequences(0, pagination.pageSize, statusFilter);
  }, [statusFilter]);

  const handleTableChange = (newPagination: any) => {
    loadSequences(newPagination.current - 1, newPagination.pageSize, statusFilter);
  };

  const handleDelete = async (id: number) => {
    try {
      await sequenceApi.deleteSequence(id);
      notification.success({
        message: 'Sequence deleted successfully',
      });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Failed to delete sequence',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await sequenceApi.activateSequence(id);
      notification.success({
        message: 'Sequence activated successfully',
      });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Failed to activate sequence',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleDeactivate = async (id: number) => {
    try {
      await sequenceApi.deactivateSequence(id);
      notification.success({
        message: 'Sequence deactivated successfully',
      });
      loadSequences(pagination.current - 1, pagination.pageSize, statusFilter);
    } catch (error: any) {
      notification.error({
        message: 'Failed to deactivate sequence',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const getStatusColor = (status: SequenceStatus) => {
    switch (status) {
      case 'ACTIVE':
        return 'green';
      case 'INACTIVE':
        return 'orange';
      case 'DRAFT':
        return 'default';
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
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      filteredValue: searchText ? [searchText] : null,
      onFilter: (value: any, record: SequenceResponse) =>
        record.name.toLowerCase().includes(value.toLowerCase()),
    },
    {
      title: 'Description',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: SequenceStatus) => (
        <Tag color={getStatusColor(status)}>{status}</Tag>
      ),
    },
    {
      title: 'Steps',
      key: 'steps',
      render: (_: any, record: SequenceResponse) => record.steps.length,
    },
    {
      title: 'Created At',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleDateString(),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: any, record: SequenceResponse) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/sequences/${record.id}`)}
          >
            View
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/sequences/${record.id}/edit`)}
          >
            Edit
          </Button>
          {record.status === 'ACTIVE' ? (
            <Button
              type="link"
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleDeactivate(record.id)}
            >
              Deactivate
            </Button>
          ) : (
            <Button
              type="link"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => handleActivate(record.id)}
              disabled={record.steps.length === 0}
            >
              Activate
            </Button>
          )}
          <Popconfirm
            title="Are you sure to delete this sequence?"
            onConfirm={() => handleDelete(record.id)}
            okText="Yes"
            cancelText="No"
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              Delete
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Sequences</h2>
        <Space>
          <Input.Search
            placeholder="Search by name"
            onSearch={setSearchText}
            style={{ width: 200 }}
            allowClear
          />
          <Select
            placeholder="Filter by status"
            style={{ width: 150 }}
            allowClear
            onChange={setStatusFilter}
            value={statusFilter}
          >
            <Select.Option value="ACTIVE">ACTIVE</Select.Option>
            <Select.Option value="INACTIVE">INACTIVE</Select.Option>
            <Select.Option value="DRAFT">DRAFT</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sequences/new')}>
            Create Sequence
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={sequences}
        loading={loading}
        rowKey="id"
        pagination={pagination}
        onChange={handleTableChange}
      />
    </div>
  );
};
