/**
 * EventLogPanel — таймлайн событий для выбранного инстанса (P7-4).
 *
 * Отображает Ant Design Timeline с записями из WS-канала event-log,
 * отфильтрованными по instanceId. Авто-скролл к последнему событию.
 */
import React, { useEffect, useRef } from 'react';
import { Drawer, Timeline, Tag, Typography, Empty, Space } from 'antd';
import {
  PlayCircleOutlined,
  CheckCircleOutlined,
  StopOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import { useInstancesStore, type EventLogEntry } from '../../store/instancesStore';
import { useEditorI18n } from '../../i18n/useEditorI18n';

const { Text } = Typography;

// ── Иконки и цвета по типу события ──────────────────────────────────────────
const EVENT_META: Record<
  EventLogEntry['eventType'],
  { color: string; icon: React.ReactNode }
> = {
  SEQUENCE_STARTED:  { color: '#0a84ff', icon: <PlayCircleOutlined /> },
  STEP_COMPLETED:    { color: '#30d158', icon: <CheckCircleOutlined /> },
  SEQUENCE_STOPPED:  { color: '#8e8e93', icon: <StopOutlined /> },
  SEQUENCE_ABORTED:  { color: '#ff453a', icon: <CloseCircleOutlined /> },
};

// ── Props ─────────────────────────────────────────────────────────────────────
interface EventLogPanelProps {
  instanceId: number | null;
  open: boolean;
  onClose: () => void;
}

// ── Компонент ─────────────────────────────────────────────────────────────────
export const EventLogPanel: React.FC<EventLogPanelProps> = ({
  instanceId,
  open,
  onClose,
}) => {
  const d = useEditorI18n();
  const allEvents = useInstancesStore((s) => s.eventLog);
  const bottomRef = useRef<HTMLDivElement>(null);

  // Фильтрация событий по инстансу
  const events = instanceId !== null
    ? allEvents.filter((e) => e.instanceId === instanceId)
    : [];

  // Авто-скролл при появлении новых событий
  useEffect(() => {
    if (open && bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [events.length, open]);

  // Формат времени
  const formatTime = (iso: string) =>
    new Date(iso).toLocaleTimeString('ru-RU', {
      hour:   '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });

  const timelineItems = events.map((entry) => {
    const meta = EVENT_META[entry.eventType] ?? {
      color: '#8e8e93',
      icon: null,
    };
    return {
      key:   String(entry.id),
      color: meta.color,
      dot:   meta.icon,
      children: (
        <Space direction="vertical" size={2} className="fade-in">
          <Space size={6} wrap>
            <Tag
              color={meta.color}
              style={{ fontSize: 11, margin: 0 }}
            >
              {d.eventTypes[entry.eventType] ?? entry.eventType}
            </Tag>
            {entry.stepIndex !== null && (
              <Text style={{ fontSize: 11 }}>
                {d.eventStep} {entry.stepIndex}
              </Text>
            )}
          </Space>
          <Text type="secondary" style={{ fontSize: 11 }}>
            {formatTime(entry.createdAt)}
          </Text>
          {entry.correlationId && (
            <Text type="secondary" style={{ fontSize: 10 }}>
              {d.correlationId}: {entry.correlationId}
            </Text>
          )}
        </Space>
      ),
    };
  });

  return (
    <Drawer
      title={
        instanceId !== null
          ? `${d.eventLogTitle} #${instanceId}`
          : d.eventLogTitle
      }
      placement="right"
      width={420}
      open={open}
      onClose={onClose}
      destroyOnClose={false}
    >
      {events.length === 0 ? (
        <Empty
          description={d.noEvents}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          style={{ marginTop: 48 }}
        />
      ) : (
        <div style={{ padding: '8px 0' }}>
          <Timeline
            mode="left"
            items={timelineItems}
          />
          {/* Якорь авто-скролла */}
          <div ref={bottomRef} />
        </div>
      )}
    </Drawer>
  );
};
