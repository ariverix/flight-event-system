import React, { useState, useEffect } from 'react';
import { Table, Tag, notification, Select, Input, Space, Button, DatePicker } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { messageApi } from '../../api/messageApi';
import { MessageResponse } from '../../types/message';
import { MessageType } from '../../types/sequence';
import type { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;

export const MessageLog: React.FC = () => {
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [messageTypeFilter, setMessageTypeFilter] = useState<MessageType | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const loadMessages = async (
    page: number = 0,
    size: number = 20,
    aircraftId?: string,
    messageType?: string,
    startDate?: string,
    endDate?: string
  ) => {
    setLoading(true);
    try {
      const data = await messageApi.getMessages(page, size, aircraftId, messageType, startDate, endDate);
      setMessages(data.content);
      setPagination({
        current: page + 1,
        pageSize: size,
        total: data.totalElements,
      });
    } catch (error: any) {
      notification.error({
        message: 'Failed to load messages',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
    const endDate = dateRange?.[1]?.format('YYYY-MM-DD');
    loadMessages(0, pagination.pageSize, aircraftIdFilter, messageTypeFilter, startDate, endDate);
  }, [messageTypeFilter, aircraftIdFilter, dateRange]);

  const handleTableChange = (newPagination: any) => {
    const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
    const endDate = dateRange?.[1]?.format('YYYY-MM-DD');
    loadMessages(
      newPagination.current - 1,
      newPagination.pageSize,
      aircraftIdFilter,
      messageTypeFilter,
      startDate,
      endDate
    );
  };

  const getMessageTypeColor = (type: MessageType) => {
    switch (type) {
      case 'DOWNLINK':
        return 'blue';
      case 'UPLINK':
        return 'green';
      case 'GROUND':
        return 'orange';
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
      title: 'Type',
      dataIndex: 'messageType',
      key: 'messageType',
      render: (type: MessageType) => (
        <Tag color={getMessageTypeColor(type)}>{type}</Tag>
      ),
    },
    {
      title: 'Template',
      dataIndex: 'templateName',
      key: 'templateName',
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
      title: 'Received At',
      dataIndex: 'receivedAt',
      key: 'receivedAt',
      render: (date: string) => new Date(date).toLocaleString(),
    },
    {
      title: 'Metadata',
      dataIndex: 'metadataJson',
      key: 'metadataJson',
      ellipsis: true,
      render: (metadata: string | null) => {
        if (!metadata) return 'N/A';
        try {
          const parsed = JSON.parse(metadata);
          return <pre style={{ fontSize: '11px', margin: 0 }}>{JSON.stringify(parsed, null, 2)}</pre>;
        } catch {
          return metadata;
        }
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>Message Log</h2>
        <Space>
          <Input
            placeholder="Filter by Aircraft ID"
            style={{ width: 200 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder="Filter by type"
            style={{ width: 150 }}
            allowClear
            onChange={setMessageTypeFilter}
            value={messageTypeFilter}
          >
            <Select.Option value="DOWNLINK">DOWNLINK</Select.Option>
            <Select.Option value="UPLINK">UPLINK</Select.Option>
            <Select.Option value="GROUND">GROUND</Select.Option>
          </Select>
          <RangePicker
            onChange={(dates) => setDateRange(dates as [Dayjs | null, Dayjs | null])}
            format="YYYY-MM-DD"
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
              const endDate = dateRange?.[1]?.format('YYYY-MM-DD');
              loadMessages(
                pagination.current - 1,
                pagination.pageSize,
                aircraftIdFilter,
                messageTypeFilter,
                startDate,
                endDate
              );
            }}
          >
            Refresh
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={messages}
        loading={loading}
        rowKey="id"
        pagination={pagination}
        onChange={handleTableChange}
      />
    </div>
  );
};
