import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Typography, Space, Button, Divider, Tag, Skeleton, List,
} from 'antd';
import {
  OrderedListOutlined,
  PlayCircleOutlined,
  MessageOutlined,
  CheckCircleOutlined,
  RocketOutlined,
  ReloadOutlined,
  ArrowRightOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { sequenceApi } from '../api/sequenceApi';
import { executionApi } from '../api/executionApi';
import { messageApi } from '../api/messageApi';
import { useAuth } from '../hooks/useAuth';
import { useTheme } from '../context/ThemeContext';
import { ExecutionInstanceResponse, ExecutionStatus } from '../types/execution';

const { Text } = Typography;

interface Stats {
  activeSequences: number;
  totalSequences: number;
  runningExecutions: number;
  completedExecutions: number;
  totalMessages: number;
}

const EXEC_STATUS_LABEL: Record<string, string> = {
  RUNNING:   'Выполняется',
  COMPLETED: 'Завершено',
  ABORTED:   'Прервано',
  WAITING:   'Ожидание',
};

const EXEC_STATUS_COLOR: Record<string, string> = {
  RUNNING:   'processing',
  COMPLETED: 'success',
  ABORTED:   'error',
  WAITING:   'warning',
};

// Animated counter hook
const useCountUp = (target: number, duration = 600) => {
  const [value, setValue] = useState(target);
  const prev = useRef(target);

  useEffect(() => {
    if (prev.current === target) return;
    const start = prev.current;
    const diff = target - start;
    const startTime = performance.now();
    prev.current = target;

    const step = (now: number) => {
      const elapsed = now - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setValue(Math.round(start + diff * eased));
      if (progress < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }, [target, duration]);

  return value;
};

const AnimatedStat: React.FC<{ value: number; valueStyle?: React.CSSProperties; title: React.ReactNode }> = ({
  value, valueStyle, title,
}) => {
  const displayed = useCountUp(value);
  return (
    <Statistic
      title={title}
      value={displayed}
      valueStyle={{ fontSize: 38, letterSpacing: '-0.03em', lineHeight: 1, ...valueStyle }}
    />
  );
};

export const Dashboard: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState<Stats>({
    activeSequences: 0,
    totalSequences: 0,
    runningExecutions: 0,
    completedExecutions: 0,
    totalMessages: 0,
  });
  const [recentExecutions, setRecentExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const { user } = useAuth();
  const navigate = useNavigate();
  const { isDark } = useTheme();

  const c = isDark
    ? {
        border: '#30363d',
        borderSecondary: '#21262d',
        text: '#e6edf3',
        textMuted: '#848d97',
        textDimmer: '#484f58',
        bgElevated: '#1c2128',
      }
    : {
        border: '#d0d7de',
        borderSecondary: '#d8dee4',
        text: '#1f2328',
        textMuted: '#636c76',
        textDimmer: '#9da3ab',
        bgElevated: '#f6f8fa',
      };

  const loadStats = useCallback(async () => {
    setLoading(true);
    try {
      const [activeSeq, allSeq, runningExec, completedExec, messages, recent] = await Promise.all([
        sequenceApi.getSequences(0, 1, 'ACTIVE'),
        sequenceApi.getSequences(0, 1),
        executionApi.getExecutions(0, 1, 'RUNNING'),
        executionApi.getExecutions(0, 1, 'COMPLETED'),
        messageApi.getMessages(0, 1),
        executionApi.getExecutions(0, 5),
      ]);

      setStats({
        activeSequences: activeSeq.totalElements,
        totalSequences: allSeq.totalElements,
        runningExecutions: runningExec.totalElements,
        completedExecutions: completedExec.totalElements,
        totalMessages: messages.totalElements,
      });
      setRecentExecutions(recent.content);
    } catch {
      // silently ignore — stats are non-critical, next auto-refresh will retry
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadStats, 15000);
    return () => clearInterval(interval);
  }, [loadStats]);

  const now = new Date();
  const dateStr = now.toLocaleDateString('ru-RU', {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
  });

  const statCards = [
    {
      title: 'Активные последовательности',
      value: stats.activeSequences,
      sub: `из ${stats.totalSequences} всего`,
      color: '#1677ff',
      icon: <OrderedListOutlined />,
    },
    {
      title: 'Активные выполнения',
      value: stats.runningExecutions,
      sub: stats.runningExecutions > 0 ? 'выполняются сейчас' : 'нет активных',
      color: '#00c853',
      icon: <PlayCircleOutlined />,
      pulse: stats.runningExecutions > 0,
    },
    {
      title: 'Всего сообщений',
      value: stats.totalMessages,
      sub: ' ',
      color: '#faad14',
      icon: <MessageOutlined />,
    },
    {
      title: 'Завершено выполнений',
      value: stats.completedExecutions,
      sub: ' ',
      color: '#52c41a',
      icon: <CheckCircleOutlined />,
    },
  ];

  return (
    <div className="fade-in-up">
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <div>
          <h2 className="page-title" style={{ marginBottom: 2 }}>Панель управления</h2>
          <Text style={{ color: c.textMuted, fontSize: 13 }}>
            {user?.fullName} · {dateStr.charAt(0).toUpperCase() + dateStr.slice(1)}
          </Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadStats} loading={loading}>
          Обновить
        </Button>
      </div>

      {/* Stat cards */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }} className="stagger-container">
        {statCards.map(card => (
          <Col xs={24} sm={12} lg={6} key={card.title} style={{ display: 'flex' }} className="stagger-item">
            {loading ? (
              <Card style={{ flex: 1, borderColor: c.borderSecondary }}>
                <Skeleton active paragraph={{ rows: 2 }} />
              </Card>
            ) : (
              <Card className="stat-card" style={{ borderColor: c.borderSecondary, flex: 1 }}>
                <div
                  className="stat-card-icon"
                  style={{
                    background: `${card.color}1e`,
                    color: card.color,
                    boxShadow: `0 0 22px ${card.color}3a`,
                  }}
                >
                  {card.icon}
                </div>
                <AnimatedStat
                  title={<span style={{ color: c.textMuted, fontSize: 13 }}>{card.title}</span>}
                  value={card.value}
                  valueStyle={{ color: card.color, fontWeight: 700 }}
                />
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }}>
                  {card.pulse && <span className="online-dot" />}
                  <Text style={{ color: c.textDimmer, fontSize: 12 }}>{card.sub}</Text>
                </div>
              </Card>
            )}
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]}>
        {/* Quick nav */}
        <Col xs={24} lg={10}>
          <Card
            title={<span style={{ color: c.text }}>Быстрые действия</span>}
            style={{ borderColor: c.borderSecondary, height: '100%' }}
          >
            <Row gutter={[12, 10]}>
              {[
                { label: 'Создать последовательность', desc: 'Новая ECA-последовательность шагов', path: '/sequences/new', color: '#1677ff', icon: <OrderedListOutlined /> },
                { label: 'Монитор выполнений',         desc: 'Просмотр активных экземпляров',       path: '/executions',      color: '#00c853', icon: <PlayCircleOutlined /> },
                { label: 'Журнал сообщений',           desc: 'История входящих сообщений',          path: '/messages',        color: '#faad14', icon: <MessageOutlined /> },
                { label: 'Симулятор',                  desc: 'Отправить тестовое событие',          path: '/simulator',       color: '#722ed1', icon: <RocketOutlined /> },
                { label: 'Демонстрация',               desc: 'Автоматический показ сценариев',      path: '/demo',            color: '#00c853', icon: <ExperimentOutlined /> },
              ].map(item => (
                <Col xs={24} sm={12} xl={item.path === '/demo' ? 24 : 12} key={item.path}>
                  <div
                    onClick={() => navigate(item.path)}
                    className="quick-action-card"
                    style={{
                      padding: '14px 16px',
                      borderRadius: 8,
                      border: `1px solid ${c.borderSecondary}`,
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      background: c.bgElevated,
                    }}
                    onMouseEnter={e => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = item.color;
                      (e.currentTarget as HTMLDivElement).style.background = `${item.color}0d`;
                    }}
                    onMouseLeave={e => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = c.borderSecondary;
                      (e.currentTarget as HTMLDivElement).style.background = c.bgElevated;
                    }}
                  >
                    <div style={{
                      width: 36, height: 36, borderRadius: 8,
                      background: `${item.color}1a`, color: item.color,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 16, flexShrink: 0,
                    }}>
                      {item.icon}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13, color: c.text }}>{item.label}</div>
                      <div style={{ fontSize: 12, color: c.textMuted, marginTop: 1 }}>{item.desc}</div>
                    </div>
                    <ArrowRightOutlined style={{ color: c.textDimmer, fontSize: 12 }} />
                  </div>
                </Col>
              ))}
            </Row>
          </Card>
        </Col>

        {/* Recent executions + system status */}
        <Col xs={24} lg={14}>
          <Row gutter={[16, 16]} style={{ height: '100%' }}>
            {/* Recent executions */}
            <Col xs={24}>
              <Card
                title={<span style={{ color: c.text }}>Последние выполнения</span>}
                extra={
                  <Button type="link" size="small" onClick={() => navigate('/executions')}>
                    Все выполнения →
                  </Button>
                }
                style={{ borderColor: c.borderSecondary }}
              >
                {loading ? (
                  <Skeleton active paragraph={{ rows: 4 }} />
                ) : (
                  <List
                    dataSource={recentExecutions}
                    locale={{ emptyText: 'Выполнений ещё не было' }}
                    renderItem={exec => (
                      <List.Item
                        style={{ padding: '8px 0', cursor: 'pointer', borderBottom: `1px solid ${c.borderSecondary}` }}
                        onClick={() => navigate(`/executions/${exec.id}`)}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%' }}>
                          <Tag
                            color={EXEC_STATUS_COLOR[exec.status as ExecutionStatus]}
                            style={{ margin: 0, flexShrink: 0, minWidth: 90, textAlign: 'center' }}
                          >
                            {EXEC_STATUS_LABEL[exec.status] ?? exec.status}
                          </Tag>
                          <Text style={{ color: c.text, fontWeight: 500, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {exec.sequenceName}
                          </Text>
                          <Text style={{ color: c.textMuted, fontSize: 12, flexShrink: 0 }}>
                            {exec.aircraftId}
                          </Text>
                          <Text style={{ color: c.textDimmer, fontSize: 11, flexShrink: 0 }}>
                            {new Date(exec.startedAt).toLocaleString('ru-RU')}
                          </Text>
                          <ArrowRightOutlined style={{ color: c.textDimmer, fontSize: 11, flexShrink: 0 }} />
                        </div>
                      </List.Item>
                    )}
                  />
                )}
              </Card>
            </Col>

            {/* System status */}
            <Col xs={24}>
              <Card
                title={<span style={{ color: c.text }}>Статус системы</span>}
                style={{ borderColor: c.borderSecondary }}
              >
                <Space direction="vertical" style={{ width: '100%' }} size={10}>
                  {[
                    { label: 'Сервер приложений', status: 'Онлайн',    color: '#00c853' },
                    { label: 'База данных',        status: 'Подключена', color: '#00c853' },
                    { label: 'Движок правил',      status: 'Активен',   color: '#00c853' },
                    { label: 'Outbox-публикация',  status: 'Активна',   color: '#00c853' },
                  ].map(item => (
                    <div key={item.label} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Text style={{ color: c.textMuted, fontSize: 13 }}>{item.label}</Text>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ width: 7, height: 7, borderRadius: '50%', background: item.color, display: 'inline-block' }} />
                        <Text style={{ color: item.color, fontSize: 12, fontWeight: 500 }}>{item.status}</Text>
                      </div>
                    </div>
                  ))}
                  <Divider style={{ margin: '4px 0', borderColor: c.borderSecondary }} />
                  <Text style={{ color: c.textDimmer, fontSize: 11 }}>
                    Spring Boot 3 · Spring Modulith · PostgreSQL 16
                  </Text>
                </Space>
              </Card>
            </Col>
          </Row>
        </Col>
      </Row>
    </div>
  );
};
