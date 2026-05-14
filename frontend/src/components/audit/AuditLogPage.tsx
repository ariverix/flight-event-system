import React, { useState, useEffect } from 'react';
import { Table, Tag, Select, Space, Button, notification, Tooltip } from 'antd';
import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { auditApi, AuditLogEntry } from '../../api/auditApi';
import { useTheme } from '../../context/ThemeContext';

const ENTITY_TYPE_COLORS: Record<string, string> = {
  SEQUENCE:  'blue',
  EXECUTION: 'green',
  USER:      'purple',
};

const ACTION_COLORS: Record<string, string> = {
  CREATE_SEQUENCE:     'cyan',
  UPDATE_SEQUENCE:     'geekblue',
  DELETE_SEQUENCE:     'red',
  ACTIVATE_SEQUENCE:   'green',
  DEACTIVATE_SEQUENCE: 'orange',
  ADD_STEP:            'cyan',
  UPDATE_STEP:         'geekblue',
  DELETE_STEP:         'volcano',
  REORDER_STEPS:       'blue',
  EXECUTION_STARTED:   'green',
  EXECUTION_COMPLETED: 'success',
  EXECUTION_ABORTED:   'red',
  USER_LOGIN:          'purple',
  CREATE_USER:         'cyan',
  TOGGLE_USER:         'orange',
};

const ACTION_LABELS: Record<string, string> = {
  CREATE_SEQUENCE:     'Создана посл-ть',
  UPDATE_SEQUENCE:     'Обновлена посл-ть',
  DELETE_SEQUENCE:     'Удалена посл-ть',
  ACTIVATE_SEQUENCE:   'Активирована',
  DEACTIVATE_SEQUENCE: 'Деактивирована',
  ADD_STEP:            'Добавлен шаг',
  UPDATE_STEP:         'Изменён шаг',
  DELETE_STEP:         'Удалён шаг',
  REORDER_STEPS:       'Порядок шагов',
  EXECUTION_STARTED:   'Выполнение начато',
  EXECUTION_COMPLETED: 'Выполнение завершено',
  EXECUTION_ABORTED:   'Выполнение прервано',
  USER_LOGIN:          'Вход в систему',
  CREATE_USER:         'Создан пользователь',
  TOGGLE_USER:         'Статус польз.',
};

const ENTITY_TYPE_LABELS: Record<string, string> = {
  SEQUENCE:  'Посл-ть',
  EXECUTION: 'Выполнение',
  USER:      'Пользователь',
};

export const AuditLogPage: React.FC = () => {
  const [logs, setLogs] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [entityTypeFilter, setEntityTypeFilter] = useState<string | undefined>();
  const [actionFilter, setActionFilter] = useState<string | undefined>();
  const { isDark } = useTheme();
  const c = isDark ? { textMuted: '#848d97' } : { textMuted: '#636c76' };

  const loadLogs = async (page = 0, size = 20, entityType?: string, action?: string) => {
    setLoading(true);
    try {
      const data = await auditApi.getLogs(page, size, entityType, action);
      setLogs(data.content);
      setPagination({ current: page + 1, pageSize: size, total: data.totalElements });
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки журнала аудита',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadLogs(0, pagination.pageSize, entityTypeFilter, actionFilter);
  }, [entityTypeFilter, actionFilter]);

  const handleTableChange = (pg: any) => {
    loadLogs(pg.current - 1, pg.pageSize, entityTypeFilter, actionFilter);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
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
            <span style={{ color: c.textMuted, fontSize: 12 }}>#{record.entityId}</span>
          )}
        </Space>
      ) : '—',
    },
    {
      title: 'Пользователь ID',
      dataIndex: 'userId',
      key: 'userId',
      width: 130,
      render: (id: number | null) => id ? `ID ${id}` : 'Система',
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
          const text = JSON.stringify(parsed, null, 2);
          return (
            <Tooltip title={<pre style={{ fontSize: 11, margin: 0 }}>{text}</pre>}>
              <span style={{ cursor: 'help', color: c.textMuted, fontSize: 12 }}>
                {Object.keys(parsed).slice(0, 2).join(', ')}…
              </span>
            </Tooltip>
          );
        } catch {
          return details;
        }
      },
    },
    {
      title: 'Время',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <SafetyCertificateOutlined style={{ fontSize: 20, color: '#1677ff' }} />
          <h2 className="page-title">Журнал аудита</h2>
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
            <Select.Option value="CREATE_SEQUENCE">Создание посл-ти</Select.Option>
            <Select.Option value="ACTIVATE_SEQUENCE">Активация</Select.Option>
            <Select.Option value="DEACTIVATE_SEQUENCE">Деактивация</Select.Option>
            <Select.Option value="DELETE_SEQUENCE">Удаление посл-ти</Select.Option>
            <Select.Option value="EXECUTION_STARTED">Старт выполнения</Select.Option>
            <Select.Option value="EXECUTION_COMPLETED">Завершение</Select.Option>
            <Select.Option value="EXECUTION_ABORTED">Прерывание</Select.Option>
            <Select.Option value="USER_LOGIN">Вход в систему</Select.Option>
            <Select.Option value="CREATE_USER">Создание польз.</Select.Option>
          </Select>
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
        pagination={{
          ...pagination,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
        onChange={handleTableChange}
        size="small"
      />
    </div>
  );
};
