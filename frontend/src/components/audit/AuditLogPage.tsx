import React, { useState, useEffect, useCallback } from 'react';
import { Table, Tag, Select, Space, Button, Tooltip } from 'antd';
import { ReloadOutlined, SafetyCertificateOutlined, DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import { auditApi, AuditLogEntry } from '../../api/auditApi';
import { useNotification } from '../../hooks/useNotification';
import { useEditorI18n } from '../../i18n/useEditorI18n';

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

export const AuditLogPage: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
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
        message: d.auditLoadError,
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
    const headers = d.auditCsvHeaders;
    const rows = logs.map(l => [
      l.id,
      d.auditActionLabels[l.action] ?? l.action,
      d.auditEntityLabels[l.entityType ?? ''] ?? (l.entityType ?? ''),
      l.entityId ?? '',
      l.userId != null ? `ID ${l.userId}` : d.auditSystemUser,
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
    notification.success({ message: `${d.auditExportedPrefix} ${logs.length} ${d.auditRecordsWord}` });
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
      title: d.auditColAction,
      dataIndex: 'action',
      key: 'action',
      render: (action: string) => (
        <Tag color={ACTION_COLORS[action] ?? 'default'}>
          {d.auditActionLabels[action] ?? action}
        </Tag>
      ),
    },
    {
      title: d.auditColEntity,
      dataIndex: 'entityType',
      key: 'entityType',
      render: (type: string | null, record: AuditLogEntry) => type ? (
        <Space size={4}>
          <Tag color={ENTITY_TYPE_COLORS[type] ?? 'default'}>
            {d.auditEntityLabels[type] ?? type}
          </Tag>
          {record.entityId && (
            <span style={{ color: 'var(--text-3)', fontSize: 12 }}>#{record.entityId}</span>
          )}
        </Space>
      ) : '—',
    },
    {
      title: d.auditColUser,
      dataIndex: 'userId',
      key: 'userId',
      width: 130,
      render: (id: number | null) => id ? (
        <span style={{ fontSize: 12, color: 'var(--text-2)' }}>ID {id}</span>
      ) : (
        <Tag color="purple" style={{ fontSize: 10 }}>{d.auditSystemUser}</Tag>
      ),
    },
    {
      title: d.auditColDetails,
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
      title: d.tlDetailsTimeLabel,
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
          <h2 className="page-title">{d.auditPageTitle}</h2>
          {logs.length > 0 && (
            <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
              {pagination.total} {d.auditRecordsWord}
            </span>
          )}
        </div>
        <Space>
          <Select
            placeholder={d.auditEntityFilterPlaceholder}
            style={{ width: 170 }}
            allowClear
            onChange={setEntityTypeFilter}
            value={entityTypeFilter}
          >
            <Select.Option value="SEQUENCE">{d.auditEntityLabels.SEQUENCE}</Select.Option>
            <Select.Option value="EXECUTION">{d.auditEntityLabels.EXECUTION}</Select.Option>
            <Select.Option value="USER">{d.auditEntityLabels.USER}</Select.Option>
          </Select>
          <Select
            placeholder={d.auditActionFilterPlaceholder}
            style={{ width: 200 }}
            allowClear
            onChange={setActionFilter}
            value={actionFilter}
          >
            <Select.Option value="CREATE_SEQUENCE">{d.auditActionFilterLabels.CREATE_SEQUENCE}</Select.Option>
            <Select.Option value="ACTIVATE_SEQUENCE">{d.auditActionFilterLabels.ACTIVATE_SEQUENCE}</Select.Option>
            <Select.Option value="DEACTIVATE_SEQUENCE">{d.auditActionFilterLabels.DEACTIVATE_SEQUENCE}</Select.Option>
            <Select.Option value="DELETE_SEQUENCE">{d.auditActionFilterLabels.DELETE_SEQUENCE}</Select.Option>
            <Select.Option value="EXECUTION_STARTED">{d.auditActionFilterLabels.EXECUTION_STARTED}</Select.Option>
            <Select.Option value="EXECUTION_COMPLETED">{d.auditActionFilterLabels.EXECUTION_COMPLETED}</Select.Option>
            <Select.Option value="EXECUTION_ABORTED">{d.auditActionFilterLabels.EXECUTION_ABORTED}</Select.Option>
            <Select.Option value="USER_LOGIN">{d.auditActionFilterLabels.USER_LOGIN}</Select.Option>
          </Select>
          <Button
            className="btn-export"
            icon={<DownloadOutlined />}
            onClick={exportCSV}
            disabled={logs.length === 0}
          >
            {d.auditExportCsvBtn}
          </Button>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => loadLogs(pagination.current - 1, pagination.pageSize, entityTypeFilter, actionFilter)}
          >
            {d.refreshBtn}
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
          showTotal: (total, range) => `${range[0]}–${range[1]} ${d.paginationOf} ${total}`,
        }}
        onChange={handleTableChange}
        size="small"
        locale={{
          emptyText: (
            <div style={{ padding: '40px 0', textAlign: 'center' }}>
              <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
              <div style={{ color: 'var(--text-3)', fontSize: 14 }}>{d.auditEmptyText}</div>
              <div style={{ color: 'var(--text-4)', fontSize: 12, marginTop: 4 }}>
                {d.auditEmptyHint}
              </div>
            </div>
          ),
        }}
      />
    </div>
  );
};
