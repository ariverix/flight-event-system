import React, { useState, useEffect } from 'react';
import { Table, Tag, notification, Select, Input, Space, Button, DatePicker, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { messageApi } from '../../api/messageApi';
import { MessageResponse } from '../../types/message';
import { MessageType } from '../../types/sequence';
import type { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;

const MSG_TYPE_LABEL: Record<string, string> = {
  DOWNLINK: 'Нисходящая',
  UPLINK:   'Восходящая',
  GROUND:   'Наземная',
};

const MSG_TYPE_COLOR: Record<string, string> = {
  DOWNLINK: 'blue',
  UPLINK:   'green',
  GROUND:   'orange',
};

export const MessageLog: React.FC = () => {
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [messageTypeFilter, setMessageTypeFilter] = useState<MessageType | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const loadMessages = async (
    page = 0,
    size = 20,
    aircraftId?: string,
    messageType?: string,
    startDate?: string,
    endDate?: string,
  ) => {
    setLoading(true);
    try {
      const data = await messageApi.getMessages(page, size, aircraftId, messageType, startDate, endDate);
      setMessages(data.content);
      setPagination({ current: page + 1, pageSize: size, total: data.totalElements });
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки журнала',
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

  const handleTableChange = (pg: any) => {
    const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
    const endDate = dateRange?.[1]?.format('YYYY-MM-DD');
    loadMessages(pg.current - 1, pg.pageSize, aircraftIdFilter, messageTypeFilter, startDate, endDate);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    {
      title: 'Тип',
      dataIndex: 'messageType',
      key: 'messageType',
      render: (type: MessageType) => (
        <Tag color={MSG_TYPE_COLOR[type]}>{MSG_TYPE_LABEL[type] ?? type}</Tag>
      ),
    },
    { title: 'Шаблон', dataIndex: 'templateName', key: 'templateName' },
    { title: 'Идент. ВС', dataIndex: 'aircraftId', key: 'aircraftId' },
    {
      title: 'Номер рейса',
      dataIndex: 'flightNumber',
      key: 'flightNumber',
      render: (v: string | null) => v || '—',
    },
    {
      title: 'Получено',
      dataIndex: 'receivedAt',
      key: 'receivedAt',
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
    {
      title: 'Метаданные',
      dataIndex: 'metadataJson',
      key: 'metadataJson',
      ellipsis: true,
      render: (metadata: string | null) => {
        if (!metadata) return '—';
        try {
          const parsed = JSON.parse(metadata);
          const keys = Object.keys(parsed).slice(0, 2).join(', ');
          const summary = keys ? `{${keys}…}` : '{}';
          return (
            <Tooltip
              title={<pre style={{ fontSize: 11, margin: 0 }}>{JSON.stringify(parsed, null, 2)}</pre>}
              placement="topLeft"
            >
              <span style={{ cursor: 'help', fontSize: 12 }}>{summary}</span>
            </Tooltip>
          );
        } catch {
          return metadata;
        }
      },
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Журнал сообщений</h2>
        <Space wrap>
          <Input
            placeholder="Фильтр по идент. ВС"
            style={{ width: 190 }}
            allowClear
            onChange={(e) => setAircraftIdFilter(e.target.value || undefined)}
          />
          <Select
            placeholder="Тип сообщения"
            style={{ width: 170 }}
            allowClear
            onChange={setMessageTypeFilter}
            value={messageTypeFilter}
          >
            <Select.Option value="DOWNLINK">Нисходящая</Select.Option>
            <Select.Option value="UPLINK">Восходящая</Select.Option>
            <Select.Option value="GROUND">Наземная</Select.Option>
          </Select>
          <RangePicker
            onChange={(dates) => setDateRange(dates as [Dayjs | null, Dayjs | null])}
            format="DD.MM.YYYY"
            placeholder={['Дата от', 'Дата до']}
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
                endDate,
              );
            }}
          >
            Обновить
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={messages}
        loading={loading}
        rowKey="id"
        pagination={{
          ...pagination,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
        onChange={handleTableChange}
      />
    </div>
  );
};
