/**
 * InstancesDashboard — реал-тайм таблица инстансов последовательностей (P7-4).
 *
 * Поведение:
 * 1. При монтировании: загружает активные инстансы (RUNNING + WAITING) из REST API.
 * 2. Вызывает instancesStore.connect() — открывает WS и подписывается на instance-status.
 * 3. WS-обновления мержатся в таблицу: status и currentStepIndex обновляются в реальном времени.
 * 4. Кнопка «Детали» открывает EventLogPanel для выбранного инстанса.
 * 5. ConnectionStatus отображается в шапке таблицы.
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Skeleton,
  Tooltip,
  Badge,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ReloadOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import { executionApi } from '../../api/executionApi';
import type { ExecutionInstanceResponse } from '../../types/execution';
import { useInstancesStore, type InstanceStatus } from '../../store/instancesStore';
import { EventLogPanel } from './EventLogPanel';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';

const { Text, Title } = Typography;

// ── Локальный тип строки таблицы ─────────────────────────────────────────────
interface TableRow {
  instanceId: number;
  sequenceId: number;
  sequenceName: string;
  aircraftId: string;
  flightNumber: string | null;
  status: InstanceStatus['status'];
  currentStepIndex: number | null;
  startedAt: string;
}

// ── Цвета статусов ────────────────────────────────────────────────────────────
const STATUS_TAG_COLOR: Record<InstanceStatus['status'], string> = {
  RUNNING:   'processing',
  WAITING:   'warning',
  COMPLETED: 'success',
  ABORTED:   'error',
};

const STATUS_DOT_COLOR: Record<InstanceStatus['status'], string> = {
  RUNNING:   '#1677ff',
  WAITING:   '#faad14',
  COMPLETED: '#52c41a',
  ABORTED:   '#ff4d4f',
};

// ── Конвертер REST → TableRow ─────────────────────────────────────────────────
function fromApiInstance(e: ExecutionInstanceResponse): TableRow {
  return {
    instanceId:       e.id,
    sequenceId:       e.sequenceId,
    sequenceName:     e.sequenceName,
    aircraftId:       e.aircraftId,
    flightNumber:     e.flightNumber,
    status:           e.status,
    currentStepIndex: e.currentStepIndex,
    startedAt:        e.startedAt,
  };
}

// ── Компонент ─────────────────────────────────────────────────────────────────
export const InstancesDashboard: React.FC = () => {
  const d = useEditorI18n();
  const { isDark } = useTheme();
  const { instances, connected, connect, disconnect } = useInstancesStore();

  const [rows, setRows]               = useState<TableRow[]>([]);
  const [loading, setLoading]         = useState(true);
  const [selectedId, setSelectedId]   = useState<number | null>(null);
  const [panelOpen, setPanelOpen]     = useState(false);

  // ── Загрузка из REST API ───────────────────────────────────────────────────
  const loadFromApi = useCallback(async () => {
    setLoading(true);
    try {
      const [running, waiting] = await Promise.all([
        executionApi.getExecutions(0, 200, 'RUNNING'),
        executionApi.getExecutions(0, 200, 'WAITING'),
      ]);
      const merged = [
        ...running.content.map(fromApiInstance),
        ...waiting.content.map(fromApiInstance),
      ];
      // Дедупликация по instanceId
      const deduped = Array.from(
        new Map(merged.map((r) => [r.instanceId, r])).values(),
      );
      setRows(deduped);
    } catch {
      // Ошибка не блокирует WS-обновления
    } finally {
      setLoading(false);
    }
  }, []);

  // ── Инициализация при монтировании ────────────────────────────────────────
  useEffect(() => {
    loadFromApi();
    connect();
    return () => {
      disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Мёрж WS-обновлений в строки таблицы ───────────────────────────────────
  useEffect(() => {
    const wsValues = Object.values(instances);
    if (wsValues.length === 0) return;

    setRows((prev) => {
      let updated = [...prev];
      for (const ws of wsValues) {
        const idx = updated.findIndex((r) => r.instanceId === ws.instanceId);
        if (idx >= 0) {
          // Обновляем статус и шаг
          updated[idx] = {
            ...updated[idx],
            status:           ws.status,
            currentStepIndex: ws.currentStepIndex,
          };
        } else {
          // Новый инстанс, пришедший только по WS (без предварительного REST-запроса)
          updated = [
            ...updated,
            {
              instanceId:       ws.instanceId,
              sequenceId:       ws.sequenceId,
              sequenceName:     `Seq #${ws.sequenceId}`,
              aircraftId:       ws.aircraftId,
              flightNumber:     ws.flightNumber,
              status:           ws.status,
              currentStepIndex: ws.currentStepIndex,
              startedAt:        ws.updatedAt,
            },
          ];
        }
      }
      return updated;
    });
  }, [instances]);

  // ── Колонки таблицы ───────────────────────────────────────────────────────
  const columns: ColumnsType<TableRow> = [
    {
      title:     d.colSequence,
      dataIndex: 'sequenceName',
      key:       'sequenceName',
      render:    (name: string, row) => (
        <Space direction="vertical" size={0}>
          <Text strong style={{ fontSize: 13 }}>{name}</Text>
          <Text type="secondary" style={{ fontSize: 11 }}>#{row.instanceId}</Text>
        </Space>
      ),
    },
    {
      title:     d.colAircraft,
      dataIndex: 'aircraftId',
      key:       'aircraftId',
      render:    (id: string) => <Text code>{id}</Text>,
    },
    {
      title:     d.colFlight,
      dataIndex: 'flightNumber',
      key:       'flightNumber',
      render:    (fn: string | null) =>
        fn ? <Text>{fn}</Text> : <Text type="secondary">—</Text>,
    },
    {
      title:     d.colStep,
      dataIndex: 'currentStepIndex',
      key:       'currentStepIndex',
      width:     100,
      render:    (step: number | null) =>
        step !== null
          ? <Tag>{d.stepLabel} {step}</Tag>
          : <Text type="secondary">—</Text>,
    },
    {
      title:  d.colStatus,
      key:    'status',
      width:  140,
      render: (_, row) => (
        <Space size={6}>
          <span
            style={{
              display:      'inline-block',
              width:        8,
              height:       8,
              borderRadius: '50%',
              background:   STATUS_DOT_COLOR[row.status],
              flexShrink:   0,
            }}
          />
          <Tag color={STATUS_TAG_COLOR[row.status]} style={{ margin: 0 }}>
            {d.instanceStatuses[row.status] ?? row.status}
          </Tag>
        </Space>
      ),
    },
    {
      title:     d.colStarted,
      dataIndex: 'startedAt',
      key:       'startedAt',
      render:    (iso: string) =>
        new Date(iso).toLocaleString('ru-RU', {
          day:    '2-digit',
          month:  '2-digit',
          hour:   '2-digit',
          minute: '2-digit',
        }),
    },
    {
      title:  d.colActions,
      key:    'actions',
      width:  90,
      render: (_, row) => (
        <Tooltip title={d.detailsBtn}>
          <Button
            size="small"
            icon={<FileTextOutlined />}
            onClick={() => {
              setSelectedId(row.instanceId);
              setPanelOpen(true);
            }}
          >
            {d.detailsBtn}
          </Button>
        </Tooltip>
      ),
    },
  ];

  // ── Цвета темы ────────────────────────────────────────────────────────────
  const borderColor = isDark ? '#30363d' : '#d0d7de';

  return (
    <div className="fade-in-up">
      {/* Заголовок */}
      <div
        style={{
          display:        'flex',
          justifyContent: 'space-between',
          alignItems:     'center',
          marginBottom:   16,
        }}
      >
        <div>
          <Title level={4} style={{ margin: 0 }}>
            {d.dashboardTitle}
          </Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            {d.dashboardSubtitle}
          </Text>
        </div>

        <Space>
          {/* Индикатор WS */}
          <Badge
            status={connected ? 'success' : 'error'}
            text={
              <Text style={{ fontSize: 12 }}>
                {connected ? d.wsConnected : d.wsDisconnected}
              </Text>
            }
          />

          {/* Кнопка обновления из REST */}
          <Button
            icon={<ReloadOutlined />}
            onClick={loadFromApi}
            loading={loading}
            size="small"
          >
            {d.refreshBtn}
          </Button>
        </Space>
      </div>

      {/* Таблица инстансов */}
      {loading ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : (
        <Table<TableRow>
          columns={columns}
          dataSource={rows}
          rowKey="instanceId"
          size="middle"
          locale={{ emptyText: d.noInstances }}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          style={{ border: `1px solid ${borderColor}`, borderRadius: 8 }}
          rowClassName={(row) =>
            row.status === 'RUNNING' ? 'instance-row-running' : ''
          }
        />
      )}

      {/* Панель Event Log */}
      <EventLogPanel
        instanceId={selectedId}
        open={panelOpen}
        onClose={() => setPanelOpen(false)}
      />
    </div>
  );
};
