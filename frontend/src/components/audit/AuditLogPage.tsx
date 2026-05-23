import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Select, Space, Button, Tooltip } from 'antd';
import { ReloadOutlined, SafetyCertificateOutlined, DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import { auditApi, AuditLogEntry } from '../../api/auditApi';
import { useNotification } from '../../hooks/useNotification';

const ENTITY_TYPE_COLORS: Record<string, string> = {
  SEQUENCE:  'blue',
  EXECUTION: 'success',
  USER:      'purple',
};

const ACTION_COLORS: Record<string, string> = {
  CREATE_SEQUENCE:     'processing',
  UPDATE_SEQUENCE:     'geekblue',
  DELETE_SEQUENCE:     'error',
  ACTIVATE_SEQUENCE:   'success',
  DEACTIVATE_SEQUENCE: 'warning',
  ADD_STEP:            'processing',
  UPDATE_STEP:         'geekblue',
  DELETE_STEP:         'error',
  REORDER_STEPS:       'blue',
  EXECUTION_STARTED:   'processing',
  EXECUTION_COMPLETED: 'success',
  EXECUTION_ABORTED:   'error',
  USER_LOGIN:          'purple',
  USER_REGISTERED:     'purple',
  CREATE_USER:         'purple',
  TOGGLE_USER:         'warning',
  USER_TOGGLED:        'warning',
};

const ACTION_LABELS: Record<string, string> = {
  CREATE_SEQUENCE:     'Создана последовательность',
  UPDATE_SEQUENCE:     'Обновлена последовательность',
  DELETE_SEQUENCE:     'Удалена последовательность',
  ACTIVATE_SEQUENCE:   'Активирована',
  DEACTIVATE_SEQUENCE: 'Деактивирована',
  ADD_STEP:            'Добавлен шаг',
  UPDATE_STEP:         'Изменён шаг',
  DELETE_STEP:         'Удалён шаг',
  REORDER_STEPS:       'Порядок шагов изменён',
  EXECUTION_STARTED:   'Выполнение начато',
  EXECUTION_COMPLETED: 'Выполнение завершено',
  EXECUTION_ABORTED:   'Выполнение прервано',
  USER_LOGIN:          'Вход в систему',
  CREATE_USER:         'Создан пользователь',
  TOGGLE_USER:         'Изменение статуса',
  USER_TOGGLED:        'Статус пользователя',
  USER_REGISTERED:     'Регистрация пользователя',
};

const ENTITY_TYPE_LABELS: Record<string, string> = {
  SEQUENCE:  'Последовательность',
  EXECUTION: 'Выполнение',
  USER:      'Пользователь',
};

export const AuditLogPage: React.FC = () => {
  const notification = useNotification();
  const [logs, setLogs] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [entityTypeFilter, setEntityTypeFilter] = useState<string | undefined>();
  const [actionFilter, setActionFilter] = useState<string | undefined>();

  const loadLogs = useCallback(async (page = 0, size = 20, entityType?: string, action?: string) => {
    setLoading(true);
    try {
      const data = await auditApi.getLogs(page, size, entityType, action);
      setLogs(data.content);
      setPagination(prev => ({ ...prev, current: page + 1, pageSize: size, total: data.totalElements }));
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки журнала аудита',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadLogs(0, pagination.pageSize, entityTypeFilter, actionFilter);
  }, [entityTypeFilter, actionFilter, loadLogs]);

  const handleTableChange = useCallback((pg: { current?: number; pageSize?: number }) => {
    loadLogs((pg.current ?? 1) - 1, pg.pageSize ?? 20, entityTypeFilter, actionFilter);
  }, [entityTypeFilter, actionFilter, loadLogs]);

  const exportCSV = useCallback(() => {
    const BOM = '﻿';
    const headers = ['ID', 'Операция', 'Тип сущности', 'ID сущности', 'Пользователь', 'Детали', 'Время'];
    const rows = logs.map(l => [
      l.id,
      ACTION_LABELS[l.action] ?? l.action,
      ENTITY_TYPE_LABELS[l.entityType ?? ''] ?? (l.entityType ?? ''),
      l.entityId ?? '',
      l.userId != null ? `ID ${l.userId}` : 'Система',
      l.detailsJson ?? '',
      new Date(l.createdAt).toLocaleString('ru-RU'),
    ]);
    const csv = [headers, ...rows]
      .map(row => row.map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))
      .join('\r\n');
    const blob = new Blob([BOM + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    notification.success({ message: `Экспортировано ${logs.length} записей` });
  }, [logs]);

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 70,
      sorter: (a: AuditLogEntry, b: AuditLogEntry) => a.id - b.id,
    },
    {
      title: 'Операция',
      dataIndex: 'action',
      key: 'action',
      render: (action: string) => (
        <Tag color={ACTION_COLORS[action] ?? 'default'}>
          {ACTION_LABELS[action] ?? action}
        </Tag>
      ),
    },
    {
      title: 'Сущность',
      dataIndex: 'entityType',
      key: 'entityType',
      render: (type: string | null, record: AuditLogEntry) => type ? (
        <Space size={4}>
          <Tag color={ENTITY_TYPE_COLORS[type] ?? 'default'}>
            {ENTITY_TYPE_LABELS[type] ?? type}
          </Tag>
          {record.entityId && (
            <span style={{ color: 'var(--text-3)', fontSize: 12 }}>#{record.entityId}</span>
          )}
        </Space>
      ) : '—',
    },
    {
      title: 'Пользователь',
      dataIndex: 'userId',
      key: 'userId',
      width: 130,
      render: (id: number | null) => id ? (
        <span style={{ fontSize: 12, color: 'var(--text-2)' }}>ID {id}</span>
      ) : (
        <Tag color="purple" style={{ fontSize: 10 }}>Система</Tag>
      ),
    },
    {
      title: 'Детали',
      dataIndex: 'detailsJson',
      key: 'detailsJson',
      ellipsis: true,
      render: (details: string | null) => {
        if (!details) return '—';
        try {
          const parsed = JSON.parse(details);
          const preview = Object.entries(parsed).slice(0, 2).map(([k, v]) => `${k}: ${String(v).slice(0, 24)}`).join(' · ');
          return (
            <Tooltip title={<pre style={{ fontSize: 11, margin: 0, maxWidth: 340 }}>{JSON.stringify(parsed, null, 2)}</pre>}>
              <code className="audit-detail-code">{preview}</code>
            </Tooltip>
          );
        } catch {
          return <span style={{ fontSize: 12 }}>{details}</span>;
        }
      },
    },
    {
      title: 'Время',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (date: string) => (
        <span style={{
          fontSize: 12,
          fontFamily: 'monospace',
          fontVariantNumeric: 'tabular-nums',
          whiteSpace: 'nowrap',
        }}>
          {date ? new Date(date).toLocaleString('ru-RU') : '—'}
        </span>
      ),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <SafetyCertificateOutlined style={{ fontSize: 20, color: 'var(--accent-blue)' }} />
          <h2 className="page-title">Журнал аудита</h2>
          {logs.length > 0 && (
            <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
              {pagination.total} записей
            </span>
          )}
        </div>
        <Space>
          <Select
            placeholder="Тип сущности"
            style={{ width: 170 }}
            allowClear
            onChange={setEntityTypeFilter}
            value={entityTypeFilter}
          >
            <Select.Option value="SEQUENCE">Последовательность</Select.Option>
            <Select.Option value="EXECUTION">Выполнение</Select.Option>
            <Select.Option value="USER">Пользователь</Select.Option>
          </Select>
          <Select
            placeholder="Тип операции"
            style={{ width: 200 }}
            allowClear
            onChange={setActionFilter}
            value={actionFilter}
          >
            <Select.Option value="CREATE_SEQUENCE">Создание последовательности</Select.Option>
            <Select.Option value="ACTIVATE_SEQUENCE">Активация</Select.Option>
            <Select.Option value="DEACTIVATE_SEQUENCE">Деактивация</Select.Option>
            <Select.Option value="DELETE_SEQUENCE">Удаление</Select.Option>
            <Select.Option value="EXECUTION_STARTED">Старт выполнения</Select.Option>
            <Select.Option value="EXECUTION_COMPLETED">Завершение</Select.Option>
            <Select.Option value="EXECUTION_ABORTED">Прерывание</Select.Option>
            <Select.Option value="USER_LOGIN">Вход в систему</Select.Option>
          </Select>
          <Button
            className="btn-export"
            icon={<DownloadOutlined />}
            onClick={exportCSV}
            disabled={logs.length === 0}
          >
            Экспорт CSV
          </Button>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadLogs(pagination.current - 1, pagination.pageSize, entityTypeFilter, actionFilter)}
          >
            Обновить
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={logs}
        loading={loading}
        rowKey="id"
        scroll={{ x: 900 }}
        pagination={{
          ...pagination,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
        onChange={handleTableChange}
        size="small"
        locale={{
          emptyText: (
            <div style={{ padding: '40px 0', textAlign: 'center' }}>
              <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
              <div style={{ color: 'var(--text-3)', fontSize: 14 }}>Записей аудита нет</div>
              <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                Действия в системе будут отражены здесь
              </div>
            </div>
          ),
        }}
      />
    </div>
  );
};
