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
import { useEditorI18n } from '../i18n/useEditorI18n';

const { Text } = Typography;

interface Stats {
  activeSequences: number;
  totalSequences: number;
  runningExecutions: number;
  completedExecutions: number;
  totalMessages: number;
  executionsToday: number;
  successRate: number | null;
}

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

const AnimatedStat: React.FC<{
  value: number;
  valueStyle?: React.CSSProperties;
  title: React.ReactNode;
  suffix?: string;
}> = ({
  value, valueStyle, title, suffix,
}) => {
  const displayed = useCountUp(value);
  return (
    <Statistic
      title={title}
      value={displayed}
      suffix={suffix}
      valueStyle={{ fontSize: 38, letterSpacing: '-0.03em', lineHeight: 1, ...valueStyle }}
    />
  );
};

export const Dashboard: React.FC = () => {
  const d = useEditorI18n();
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState<Stats>({
    activeSequences: 0,
    totalSequences: 0,
    runningExecutions: 0,
    completedExecutions: 0,
    totalMessages: 0,
    executionsToday: 0,
    successRate: null,
  });
  const [recentExecutions, setRecentExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const { user } = useAuth();
  const navigate = useNavigate();
  const { isDark } = useTheme();

  const c = isDark
    ? {
        border: 'rgba(255,255,255,0.14)',
        borderSecondary: 'rgba(255,255,255,0.09)',
        text: '#f5f5f7',
        textMuted: 'rgba(255,255,255,0.55)',
        textDimmer: 'rgba(255,255,255,0.30)',
        bgElevated: '#2c2c2e',
      }
    : {
        border: 'rgba(0,0,0,0.14)',
        borderSecondary: 'rgba(0,0,0,0.08)',
        text: '#1d1d1f',
        textMuted: '#6e6e73',
        textDimmer: '#8e8e93',
        bgElevated: '#ffffff',
      };

  const loadStats = useCallback(async () => {
    setLoading(true);
    try {
      const [activeSeq, allSeq, runningExec, completedExec, abortedExec, messages, recentSample] = await Promise.all([
        sequenceApi.getSequences(0, 1, 'ACTIVE'),
        sequenceApi.getSequences(0, 1),
        executionApi.getExecutions(0, 1, 'RUNNING'),
        executionApi.getExecutions(0, 1, 'COMPLETED'),
        executionApi.getExecutions(0, 1, 'ABORTED'),
        messageApi.getMessages(0, 1),
        // Executions are sorted newest-first server-side; a bounded sample is the
        // best we can do client-side for "today" / success-rate without a backend
        // aggregate endpoint (none exists yet — see report).
        executionApi.getExecutions(0, 100),
      ]);

      const todayStr = new Date().toDateString();
      const executionsToday = recentSample.content.filter(
        exec => new Date(exec.startedAt).toDateString() === todayStr
      ).length;

      const finished = completedExec.totalElements + abortedExec.totalElements;
      const successRate = finished > 0
        ? Math.round((completedExec.totalElements / finished) * 100)
        : null;

      setStats({
        activeSequences: activeSeq.totalElements,
        totalSequences: allSeq.totalElements,
        runningExecutions: runningExec.totalElements,
        completedExecutions: completedExec.totalElements,
        totalMessages: messages.totalElements,
        executionsToday,
        successRate,
      });
      setRecentExecutions(recentSample.content.slice(0, 5));
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

  // Системные цвета macOS — по одному плоскому акценту на карточку (без градиентов).
  const blue   = isDark ? '#0a84ff' : '#0071e3';
  const green  = isDark ? '#30d158' : '#248a3d';
  const orange = isDark ? '#ff9f0a' : '#c2410c';
  const red    = isDark ? '#ff453a' : '#d70015';
  const purple = isDark ? '#bf5af2' : '#af52de';

  const statCards = [
    {
      title: d.dashStatTotalSeq,
      value: stats.totalSequences,
      sub: d.dashSubTotalSeq,
      color: blue,
      icon: <OrderedListOutlined />,
      href: '/sequences',
    },
    {
      title: d.dashStatActiveSeq,
      value: stats.activeSequences,
      sub: `${d.paginationOf} ${stats.totalSequences} ${d.dashSubOfTotalSuffix}`,
      color: green,
      icon: <PlayCircleOutlined />,
      pulse: stats.activeSequences > 0,
      href: '/sequences',
    },
    {
      title: d.dashStatExecToday,
      value: stats.executionsToday,
      sub: stats.runningExecutions > 0 ? `${stats.runningExecutions} ${d.dashRunningNowSuffix}` : d.dashSubExecTodayDefault,
      color: orange,
      icon: <RocketOutlined />,
      pulse: stats.runningExecutions > 0,
      href: '/executions',
    },
    {
      title: d.dashStatSuccessRate,
      value: stats.successRate ?? 0,
      suffix: '%',
      sub: stats.successRate === null ? d.dashSubNoCompleted : `${stats.completedExecutions} ${d.dashSuccessfulSuffix}`,
      color: stats.successRate === null || stats.successRate >= 80 ? green : stats.successRate >= 50 ? orange : red,
      icon: <CheckCircleOutlined />,
      href: '/executions',
    },
  ];

  return (
    <div className="fade-in-up">
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <div>
          <h2 className="page-title" style={{ marginBottom: 2 }}>{d.dashHomeTitle}</h2>
          <Text style={{ color: c.textMuted, fontSize: 13 }}>
            {user?.fullName} · {dateStr.charAt(0).toUpperCase() + dateStr.slice(1)}
          </Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadStats} loading={loading}>
          {d.refreshBtn}
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
              <Card className="stat-card stat-card-gradient" style={{ borderColor: c.borderSecondary, flex: 1, '--stat-gradient': card.color } as React.CSSProperties} onClick={() => card.href && navigate(card.href)}>
                <div
                  className="stat-card-icon"
                  style={{
                    background: `${card.color}1f`,
                    color: card.color,
                  }}
                >
                  {card.icon}
                </div>
                <AnimatedStat
                  title={<span style={{ color: c.textMuted, fontSize: 13 }}>{card.title}</span>}
                  value={card.value}
                  suffix={card.suffix}
                  valueStyle={{ color: card.color, fontWeight: 700 }}
                />
                {card.suffix === '%' && (
                  <div className="stat-card-progress-track" style={{ margin: '6px 0 2px' }}>
                    <div
                      className="stat-card-progress-fill"
                      style={{ transform: `scaleX(${card.value / 100})`, background: card.color }}
                    />
                  </div>
                )}
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
            title={<span style={{ color: c.text }}>{d.dashQuickActionsTitle}</span>}
            style={{ borderColor: c.borderSecondary, height: '100%' }}
          >
            <Row gutter={[12, 10]}>
              {[
                { label: d.dashQaCreateSeqLabel, desc: d.dashQaCreateSeqDesc, path: '/sequences/new', color: blue, icon: <OrderedListOutlined /> },
                { label: d.dashQaMonitorLabel,   desc: d.dashQaMonitorDesc,   path: '/executions',     color: green, icon: <PlayCircleOutlined /> },
                { label: d.dashQaMessagesLabel,  desc: d.dashQaMessagesDesc,  path: '/messages',        color: orange, icon: <MessageOutlined /> },
                { label: d.dashQaSimulatorLabel, desc: d.dashQaSimulatorDesc, path: '/simulator',       color: purple, icon: <RocketOutlined /> },
                { label: d.dashQaDemoLabel,      desc: d.dashQaDemoDesc,      path: '/demo',            color: green, icon: <ExperimentOutlined /> },
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
                title={<span style={{ color: c.text }}>{d.dashRecentExecTitle}</span>}
                extra={
                  <Button type="link" size="small" onClick={() => navigate('/executions')}>
                    {d.dashAllExecLink}
                  </Button>
                }
                style={{ borderColor: c.borderSecondary }}
              >
                {loading ? (
                  <Skeleton active paragraph={{ rows: 4 }} />
                ) : (
                  <List
                    dataSource={recentExecutions}
                    locale={{ emptyText: d.dashRecentExecEmpty }}
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
                            {d.instanceStatuses[exec.status] ?? exec.status}
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
                title={<span style={{ color: c.text }}>{d.dashSystemStatusTitle}</span>}
                style={{ borderColor: c.borderSecondary }}
              >
                <Space direction="vertical" style={{ width: '100%' }} size={10}>
                  {[
                    { label: d.dashSvcAppServer,   status: d.dashSvcAppServerStatus,   color: green },
                    { label: d.dashSvcDatabase,    status: d.dashSvcDatabaseStatus,    color: green },
                    { label: d.dashSvcRulesEngine, status: d.dashSvcRulesEngineStatus, color: green },
                    { label: d.dashSvcOutbox,      status: d.dashSvcOutboxStatus,      color: green },
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
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Text style={{ color: c.textDimmer, fontSize: 11 }}>ECA v1.0.0</Text>
                    <Text style={{ color: c.textDimmer, fontSize: 11 }}>{d.dashAllSystemsOk}</Text>
                  </div>
                </Space>
              </Card>
            </Col>
          </Row>
        </Col>
      </Row>
    </div>
  );
};
