import React, { useState, useEffect } from 'react';
import { Layout, Menu, Button, Space, Typography, Tooltip, Badge, Dropdown, List, Empty } from 'antd';
import {
  DashboardOutlined,
  OrderedListOutlined,
  PlayCircleOutlined,
  MessageOutlined,
  UserOutlined,
  LogoutOutlined,
  ExperimentOutlined,
  RocketOutlined,
  BellOutlined,
  SafetyCertificateOutlined,
  ProfileOutlined,
  PlaySquareOutlined,
  SunOutlined,
  MoonOutlined,
  RadarChartOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useTheme } from '../../context/ThemeContext';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse } from '../../types/execution';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const ROLE_LABEL: Record<string, string> = {
  ADMIN: 'Администратор',
  OPERATOR: 'Оператор',
};

const STATUS_LABEL_RU: Record<string, string> = {
  RUNNING:   'Выполняется',
  WAITING:   'Ожидание',
  COMPLETED: 'Завершено',
  ABORTED:   'Прервано',
};

const STATUS_COLORS: Record<string, string> = {
  RUNNING:   '#1677ff',
  WAITING:   '#faad14',
  COMPLETED: '#52c41a',
  ABORTED:   '#ff4d4f',
};

export const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, isAdmin } = useAuth();
  const { isDark, toggleTheme } = useTheme();

  const [activeExecutions, setActiveExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const [notifOpen, setNotifOpen] = useState(false);
  const [siderCollapsed, setSiderCollapsed] = useState(false);

  const c = isDark
    ? {
        bgElevated: 'rgba(8,10,22,0.97)',
        border: 'rgba(255,255,255,0.09)',
        borderSecondary: 'rgba(255,255,255,0.055)',
        text: '#e6edf3',
        textMuted: '#848d97',
        textDimmer: '#484f58',
        bgContainer: 'rgba(255,255,255,0.022)',
      }
    : {
        bgElevated: '#f6f8fa',
        border: '#d0d7de',
        borderSecondary: '#d8dee4',
        text: '#1f2328',
        textMuted: '#636c76',
        textDimmer: '#9da3ab',
        bgContainer: 'rgba(255,255,255,0.82)',
      };

  // опрашиваем активные выполнения каждые 10 сек для счётчика в сайдбаре
  useEffect(() => {
    const load = async () => {
      try {
        const data = await executionApi.getExecutions(0, 10, undefined);
        setActiveExecutions(
          data.content.filter(e => e.status === 'RUNNING' || e.status === 'WAITING'),
        );
      } catch {
        // silently ignore
      }
    };
    load();
    const id = setInterval(load, 10_000);
    return () => clearInterval(id);
  }, []);

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: 'Панель управления' },
    { key: '/sequences', icon: <OrderedListOutlined />, label: 'Последовательности' },
    { key: '/executions', icon: <PlayCircleOutlined />, label: 'Выполнения' },
    { key: '/messages',  icon: <MessageOutlined />,     label: 'Журнал сообщений' },
    { key: '/timeline',  icon: <RadarChartOutlined />,  label: 'Хронология' },
    { key: '/simulator', icon: <ExperimentOutlined />,  label: 'Симулятор' },
    { key: '/demo', icon: <PlaySquareOutlined />, label: 'Демонстрация' },
    ...(isAdmin
      ? [
          { key: '/audit-log', icon: <SafetyCertificateOutlined />, label: 'Журнал аудита' },
          { key: '/users', icon: <UserOutlined />, label: 'Пользователи' },
        ]
      : []),
  ];

  const activeKey =
    location.pathname === '/'
      ? '/'
      : menuItems
          .filter(i => i.key !== '/')
          .find(i => location.pathname.startsWith(i.key))?.key ?? location.pathname;

  const notifDropdown = (
    <div
      style={{
        width: 320,
        background: c.bgElevated,
        border: `1px solid ${c.border}`,
        borderRadius: 8,
        boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          padding: '12px 16px',
          borderBottom: `1px solid ${c.borderSecondary}`,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Text strong style={{ color: c.text }}>Активные выполнения</Text>
        <Text style={{ color: c.textMuted, fontSize: 12 }}>{activeExecutions.length} активных</Text>
      </div>

      {activeExecutions.length === 0 ? (
        <div style={{ padding: '24px 16px' }}>
          <Empty description={<Text style={{ color: c.textMuted }}>Нет активных выполнений</Text>} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </div>
      ) : (
        <List
          dataSource={activeExecutions}
          renderItem={exec => (
            <List.Item
              style={{ padding: '10px 16px', cursor: 'pointer', borderBottom: `1px solid ${c.borderSecondary}` }}
              onClick={() => { navigate(`/executions/${exec.id}`); setNotifOpen(false); }}
            >
              <div style={{ width: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
                  <Text strong style={{ color: c.text, fontSize: 13 }}>
                    {exec.sequenceName}
                  </Text>
                  <span
                    style={{
                      fontSize: 11,
                      color: STATUS_COLORS[exec.status],
                      fontWeight: 500,
                    }}
                  >
                    {STATUS_LABEL_RU[exec.status] ?? exec.status}
                  </span>
                </div>
                <Text style={{ color: c.textMuted, fontSize: 12 }}>
                  ВС: {exec.aircraftId}
                  {exec.flightNumber ? ` · Рейс: ${exec.flightNumber}` : ''}
                </Text>
              </div>
            </List.Item>
          )}
        />
      )}

      <div
        style={{
          padding: '10px 16px',
          borderTop: `1px solid ${c.borderSecondary}`,
          textAlign: 'center',
        }}
      >
        <Button type="link" size="small" onClick={() => { navigate('/executions'); setNotifOpen(false); }}>
          Открыть все выполнения →
        </Button>
      </div>
    </div>
  );

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        breakpoint="lg"
        collapsedWidth="0"
        onCollapse={(collapsed) => setSiderCollapsed(collapsed)}
        width={240}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0, top: 0, bottom: 0,
          borderRight: `1px solid ${c.borderSecondary}`,
        }}
      >
        {/* Logo */}
        <div className="sidebar-logo">
          <div className="sidebar-logo-inner">
            <RocketOutlined className="sidebar-logo-icon" />
            <div className="sidebar-logo-text">
              <span className="sidebar-logo-name">СИСТЕМА ЕСА</span>
              <span className="sidebar-logo-sub">Event Control Automation</span>
            </div>
          </div>
        </div>

        <Menu
          theme={isDark ? 'dark' : 'light'}
          mode="inline"
          selectedKeys={[activeKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderRight: 'none', background: 'transparent' }}
        />

        {/* Sidebar footer */}
        <div
          style={{
            position: 'absolute',
            bottom: 0, left: 0, right: 0,
            padding: '12px 16px',
            borderTop: `1px solid ${c.borderSecondary}`,
            display: 'flex', alignItems: 'center', gap: 6,
          }}
        >
          <span className="online-dot" />
          <Text style={{ fontSize: 11, color: c.textDimmer }}>Система онлайн · v1.0.0</Text>
        </div>
      </Sider>

      <Layout style={{ marginLeft: siderCollapsed ? 0 : 240, transition: 'margin-left 0.2s ease' }}>
        <Header
          style={{
            padding: '0 24px',
            height: 64,
            lineHeight: '64px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            borderBottom: `1px solid ${c.borderSecondary}`,
            position: 'sticky',
            top: 0,
            zIndex: 100,
            overflow: 'hidden',
          }}
        >
          {/* Left: system subtitle */}
          <Badge
            status="processing"
            color="#00c853"
            text={
              <Text style={{ fontSize: 12, color: c.textMuted }}>
                Авиационная система мониторинга событий
              </Text>
            }
          />

          {/* Right: theme toggle + notifications + user */}
          <Space size={8}>
            {/* Theme toggle */}
            <Tooltip title={isDark ? 'Светлая тема' : 'Тёмная тема'}>
              <Button
                type="text"
                icon={isDark ? <SunOutlined style={{ fontSize: 16 }} /> : <MoonOutlined style={{ fontSize: 16 }} />}
                onClick={toggleTheme}
                style={{ color: c.textMuted }}
              />
            </Tooltip>

            {/* Notifications bell */}
            <Dropdown
              open={notifOpen}
              onOpenChange={setNotifOpen}
              popupRender={() => notifDropdown}
              trigger={['click']}
              placement="bottomRight"
            >
              <Tooltip title="Активные выполнения">
                <Badge count={activeExecutions.length} size="small" offset={[-2, 2]}>
                  <Button
                    type="text"
                    icon={<BellOutlined style={{ fontSize: 16 }} />}
                    style={{ color: activeExecutions.length > 0 ? '#faad14' : c.textMuted }}
                  />
                </Badge>
              </Tooltip>
            </Dropdown>

            {/* User info — показываем полное имя, Tooltip как запасной вариант */}
            <Tooltip title={user?.fullName}>
              <div
                onClick={() => navigate('/profile')}
                style={{
                  textAlign: 'right',
                  cursor: 'pointer',
                  maxWidth: 180,
                  overflow: 'hidden',
                  lineHeight: 1,
                  flexShrink: 0,
                }}
              >
                <div style={{
                  fontWeight: 600,
                  fontSize: 12,
                  color: c.text,
                  lineHeight: '17px',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                }}>
                  {user?.fullName}
                </div>
                <div style={{
                  fontSize: 11,
                  color: c.textMuted,
                  whiteSpace: 'nowrap',
                  lineHeight: '16px',
                }}>
                  {ROLE_LABEL[user?.role ?? ''] ?? user?.role}
                </div>
              </div>
            </Tooltip>

            <Tooltip title="Профиль пользователя">
              <Button
                type="text"
                icon={<ProfileOutlined />}
                onClick={() => navigate('/profile')}
                style={{ color: c.textMuted }}
              />
            </Tooltip>

            <Tooltip title="Выйти из системы">
              <Button
                type="text"
                icon={<LogoutOutlined />}
                onClick={logout}
                style={{ color: c.textMuted }}
              />
            </Tooltip>
          </Space>
        </Header>

        <Content style={{ margin: '24px', overflow: 'initial' }}>
          <div
            key={location.pathname}
            className="page-enter"
            style={{
              padding: 24,
              background: c.bgContainer,
              borderRadius: 12,
              border: `1px solid ${c.borderSecondary}`,
              minHeight: 'calc(100vh - 112px)',
            }}
          >
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
};
