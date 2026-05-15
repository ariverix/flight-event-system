import React, { useState, useEffect } from 'react';
import { Card, Row, Col, Statistic, Spin, Typography, Space, Button, Divider } from 'antd';
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

const { Text } = Typography;

interface Stats {
  activeSequences: number;
  totalSequences: number;
  runningExecutions: number;
  completedExecutions: number;
  totalMessages: number;
}

export const Dashboard: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState<Stats>({
    activeSequences: 0,
    totalSequences: 0,
    runningExecutions: 0,
    completedExecutions: 0,
    totalMessages: 0,
  });
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

  const loadStats = async () => {
    setLoading(true);
    try {
      const [activeSeq, allSeq, runningExec, completedExec, messages] = await Promise.all([
        sequenceApi.getSequences(0, 1, 'ACTIVE'),
        sequenceApi.getSequences(0, 1),
        executionApi.getExecutions(0, 1, 'RUNNING'),
        executionApi.getExecutions(0, 1, 'COMPLETED'),
        messageApi.getMessages(0, 1),
      ]);

      setStats({
        activeSequences: activeSeq.totalElements,
        totalSequences: allSeq.totalElements,
        runningExecutions: runningExec.totalElements,
        completedExecutions: completedExec.totalElements,
        totalMessages: messages.totalElements,
      });
    } catch (error) {
      console.error('Ошибка загрузки статистики:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadStats, 15000);
    return () => clearInterval(interval);
  }, []);

  const now = new Date();
  const dateStr = now.toLocaleDateString('ru-RU', {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
  });

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '400px' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="fade-in-up">
      {/* ── Header ─────────────────────────────────────────────── */}
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

      {/* ── Stat cards row 1 ────────────────────────────────── */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={12} lg={6} style={{ display: 'flex' }}>
          <Card className="stat-card" style={{ borderColor: c.borderSecondary, flex: 1 }}>
            <div
              className="stat-card-icon"
              style={{ background: 'rgba(22,119,255,0.12)', color: '#1677ff' }}
            >
              <OrderedListOutlined />
            </div>
            <Statistic
              title={<span style={{ color: c.textMuted, fontSize: 12 }}>Активные последовательности</span>}
              value={stats.activeSequences}
              valueStyle={{ color: '#1677ff', fontWeight: 700 }}
            />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>
              из {stats.totalSequences} всего
            </Text>
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6} style={{ display: 'flex' }}>
          <Card className="stat-card" style={{ borderColor: c.borderSecondary, flex: 1 }}>
            <div
              className="stat-card-icon"
              style={{ background: 'rgba(0,200,83,0.12)', color: '#00c853' }}
            >
              <PlayCircleOutlined />
            </div>
            <Statistic
              title={<span style={{ color: c.textMuted, fontSize: 12 }}>Активные выполнения</span>}
              value={stats.runningExecutions}
              valueStyle={{ color: '#00c853', fontWeight: 700 }}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }}>
              {stats.runningExecutions > 0 && <span className="online-dot" />}
              <Text style={{ color: c.textDimmer, fontSize: 12 }}>
                {stats.runningExecutions > 0 ? 'выполняются сейчас' : 'нет активных'}
              </Text>
            </div>
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6} style={{ display: 'flex' }}>
          <Card className="stat-card" style={{ borderColor: c.borderSecondary, flex: 1 }}>
            <div
              className="stat-card-icon"
              style={{ background: 'rgba(250,173,20,0.12)', color: '#faad14' }}
            >
              <MessageOutlined />
            </div>
            <Statistic
              title={<span style={{ color: c.textMuted, fontSize: 12 }}>Всего сообщений</span>}
              value={stats.totalMessages}
              valueStyle={{ color: '#faad14', fontWeight: 700 }}
            />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>&nbsp;</Text>
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6} style={{ display: 'flex' }}>
          <Card className="stat-card" style={{ borderColor: c.borderSecondary, flex: 1 }}>
            <div
              className="stat-card-icon"
              style={{ background: 'rgba(82,196,26,0.12)', color: '#52c41a' }}
            >
              <CheckCircleOutlined />
            </div>
            <Statistic
              title={<span style={{ color: c.textMuted, fontSize: 12 }}>Завершено выполнений</span>}
              value={stats.completedExecutions}
              valueStyle={{ color: '#52c41a', fontWeight: 700 }}
            />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>&nbsp;</Text>
          </Card>
        </Col>
      </Row>

      {/* ── Quick navigation ────────────────────────────────── */}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Card
            title={<span style={{ color: c.text }}>Быстрые действия</span>}
            style={{ borderColor: c.borderSecondary, height: '100%' }}
          >
            <Row gutter={[12, 10]}>
              {[
                {
                  label: 'Создать последовательность',
                  desc: 'Новая ECA-последовательность шагов',
                  path: '/sequences/new',
                  color: '#1677ff',
                  icon: <OrderedListOutlined />,
                },
                {
                  label: 'Монитор выполнений',
                  desc: 'Просмотр активных экземпляров',
                  path: '/executions',
                  color: '#00c853',
                  icon: <PlayCircleOutlined />,
                },
                {
                  label: 'Журнал сообщений',
                  desc: 'История входящих сообщений',
                  path: '/messages',
                  color: '#faad14',
                  icon: <MessageOutlined />,
                },
                {
                  label: 'Симулятор',
                  desc: 'Отправить тестовое событие',
                  path: '/simulator',
                  color: '#722ed1',
                  icon: <RocketOutlined />,
                },
                {
                  label: 'Демонстрация',
                  desc: 'Автоматический показ сценариев',
                  path: '/demo',
                  color: '#00c853',
                  icon: <ExperimentOutlined />,
                },
              ].map(item => (
                <Col xs={24} sm={12} xl={item.path === '/demo' ? 24 : 12} key={item.path}>
                  <div
                    onClick={() => navigate(item.path)}
                    style={{
                      padding: '14px 16px',
                      borderRadius: 8,
                      border: `1px solid ${c.borderSecondary}`,
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      transition: 'all 0.2s',
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
                    <div
                      style={{
                        width: 36,
                        height: 36,
                        borderRadius: 8,
                        background: `${item.color}1a`,
                        color: item.color,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 16,
                        flexShrink: 0,
                      }}
                    >
                      {item.icon}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13, color: c.text }}>
                        {item.label}
                      </div>
                      <div style={{ fontSize: 12, color: c.textMuted, marginTop: 1 }}>
                        {item.desc}
                      </div>
                    </div>
                    <ArrowRightOutlined style={{ color: c.textDimmer, fontSize: 12 }} />
                  </div>
                </Col>
              ))}
            </Row>
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card
            title={<span style={{ color: c.text }}>Статус системы</span>}
            style={{ borderColor: c.borderSecondary, height: '100%' }}
          >
            <Space direction="vertical" style={{ width: '100%' }} size={14}>
              {[
                { label: 'Сервер приложений', status: 'Онлайн', color: '#00c853' },
                { label: 'База данных', status: 'Подключена', color: '#00c853' },
                { label: 'Движок правил', status: 'Активен', color: '#00c853' },
                { label: 'Планировщик задач', status: 'Запущен', color: '#00c853' },
                { label: 'Outbox-публикация', status: 'Активна', color: '#00c853' },
              ].map(item => (
                <div
                  key={item.label}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '8px 0',
                  }}
                >
                  <Text style={{ color: c.textMuted, fontSize: 13 }}>{item.label}</Text>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span
                      style={{
                        width: 7,
                        height: 7,
                        borderRadius: '50%',
                        background: item.color,
                        display: 'inline-block',
                      }}
                    />
                    <Text style={{ color: item.color, fontSize: 12, fontWeight: 500 }}>
                      {item.status}
                    </Text>
                  </div>
                </div>
              ))}
              <Divider style={{ margin: '4px 0', borderColor: c.borderSecondary }} />
              <Text style={{ color: c.textDimmer, fontSize: 11 }}>
                Spring Boot 3 · Spring Modulith · PostgreSQL 17
              </Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
};
