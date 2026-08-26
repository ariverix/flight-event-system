import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Select, Space, Button, DatePicker, Tooltip, Skeleton } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import { ReloadOutlined, InboxOutlined } from '@ant-design/icons';
import { messageApi } from '../../api/messageApi';
import { AircraftPicker } from '../sequence/AircraftPicker';
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
  DOWNLINK: 'processing',
  UPLINK:   'success',
  GROUND:   'warning',
};

export const MessageLog: React.FC = () => {
  const notification = useNotification();
  const [messages, setMessages] = useState<MessageResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [messageTypeFilter, setMessageTypeFilter] = useState<MessageType | undefined>();
  const [aircraftIdFilter, setAircraftIdFilter] = useState<string | undefined>();
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const loadMessages = useCallback(async (
    page = 0, size = 20,
    aircraftId?: string, messageType?: string,
    startDate?: string, endDate?: string,
  ) => {
    setLoading(true);
    try {
      const data = await messageApi.getMessages(page, size, aircraftId, messageType, startDate, endDate);
      setMessages(data.content);
      setPagination(prev => ({ ...prev, current: page + 1, pageSize: size, total: data.totalElements }));
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки журнала',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
    const endDate   = dateRange?.[1]?.format('YYYY-MM-DD');
    loadMessages(0, pagination.pageSize, aircraftIdFilter, messageTypeFilter, startDate, endDate);
  }, [messageTypeFilter, aircraftIdFilter, dateRange, loadMessages]);

  const handleTableChange = useCallback((pg: { current?: number; pageSize?: number }) => {
    const startDate = dateRange?.[0]?.format('YYYY-MM-DD');
    const endDate   = dateRange?.[1]?.format('YYYY-MM-DD');
    loadMessages(
      (pg.current ?? 1) - 1, pg.pageSize ?? 20,
      aircraftIdFilter, messageTypeFilter, startDate, endDate,
    );
  }, [dateRange, aircraftIdFilter, messageTypeFilter, loadMessages]);

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    {
      title: 'Тип',
      dataIndex: 'messageType',
      key: 'messageType',
      width: 130,
      render: (type: MessageType) => (
        <Tag color={MSG_TYPE_COLOR[type] ?? 'default'}>{MSG_TYPE_LABEL[type] ?? type}</Tag>
      ),
    },
    {
      title: 'Шаблон',
      dataIndex: 'templateName',
      key: 'templateName',
      ellipsis: { showTitle: false },
      render: (v: string) => <span title={v} style={{ fontWeight: 500 }}>{v}</span>,
    },
    {
      title: 'Идент. ВС',
      dataIndex: 'aircraftId',
      key: 'aircraftId',
      width: 120,
      render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>✈ {v}</span>,
    },
    {
      title: 'Рейс',
      dataIndex: 'flightNumber',
      key: 'flightNumber',
      width: 100,
      render: (v: string | null) => v || <span style={{ color: 'var(--text-3)' }}>—</span>,
    },
    {
      title: 'Получено',
      dataIndex: 'receivedAt',
      key: 'receivedAt',
      width: 155,
      render: (date: string) => (
        <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
          {new Date(date).toLocaleString('ru-RU')}
        </span>
      ),
    },
    {
      title: 'Метаданные',
      dataIndex: 'metadataJson',
      key: 'metadataJson',
      ellipsis: true,
      render: (metadata: string | null) => {
        if (!metadata) return <span style={{ color: 'var(--text-3)' }}>—</span>;
        try {
          const parsed = JSON.parse(metadata);
          const preview = Object.keys(parsed).slice(0, 2).join(', ');
          const summary = preview ? `{${preview}…}` : '{}';
          return (
            <Tooltip
              title={<pre style={{ fontSize: 11, margin: 0, maxWidth: 300 }}>{JSON.stringify(parsed, null, 2)}</pre>}
              placement="topLeft"
            >
              <code style={{
                cursor: 'help', fontSize: 11, color: 'var(--text-2)',
                background: 'rgba(255,255,255,0.05)', borderRadius: 5,
                padding: '2px 6px', border: '1px solid rgba(255,255,255,0.07)',
              }}>{summary}</code>
            </Tooltip>
          );
        } catch {
          return <span style={{ fontSize: 12 }}>{metadata}</span>;
        }
      },
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Журнал сообщений</h2>
        <Space wrap>
          {/* Фаза 6 (aircraft-bindings): фильтр по борту — выбор из известных tail numbers
              (GET /api/v1/aircraft) вместо свободного текста */}
          <div style={{ width: 260 }}>
            <AircraftPicker
              value={aircraftIdFilter ?? null}
              onChange={setAircraftIdFilter}
            />
          </div>
          <Select
            aria-label="Тип сообщения"
            placeholder="Тип сообщения"
            style={{ width: 165 }}
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
          <Button icon={<ReloadOutlined />} onClick={() => {
            const s = dateRange?.[0]?.format('YYYY-MM-DD');
            const e = dateRange?.[1]?.format('YYYY-MM-DD');
            loadMessages(pagination.current - 1, pagination.pageSize, aircraftIdFilter, messageTypeFilter, s, e);
          }}>
            Обновить
          </Button>
        </Space>
      </div>

      {loading && messages.length === 0 ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : (
        <Table
          columns={columns}
          dataSource={messages}
          loading={loading}
          rowKey="id"
          scroll={{ x: 'max-content' }}
          locale={{
            emptyText: (
              <div style={{ padding: '40px 0', textAlign: 'center' }}>
                <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
                <div style={{ color: 'var(--text-3)', fontSize: 14 }}>Сообщений нет</div>
                <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                  Отправьте событие через Симулятор чтобы увидеть сообщения
                </div>
              </div>
            ),
          }}
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
