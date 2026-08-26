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
import { ConnectionStatus } from '../dashboard/ConnectionStatus';
import { ErrorBoundary } from '../ErrorBoundary';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { getExecutionStatusColor } from '../../utils/executionStatusColors';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

export const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, isAdmin } = useAuth();
  const { isDark, toggleTheme } = useTheme();
  const d = useEditorI18n();

  const [activeExecutions, setActiveExecutions] = useState<ExecutionInstanceResponse[]>([]);
  const [notifOpen, setNotifOpen] = useState(false);
  const [siderCollapsed, setSiderCollapsed] = useState(false);

  // Палитра оболочки приложения — macOS System Settings / Finder (нейтральные
  // поверхности, тонкие hairline-границы, без свечений).
  const c = isDark
    ? {
        bgElevated: '#2c2c2e',
        border: 'rgba(255,255,255,0.14)',
        borderSecondary: 'rgba(255,255,255,0.09)',
        text: '#f5f5f7',
        textMuted: 'rgba(255,255,255,0.55)',
        textDimmer: 'rgba(255,255,255,0.30)',
        bgContainer: '#262626',
      }
    : {
        bgElevated: '#ffffff',
        border: 'rgba(0,0,0,0.14)',
        borderSecondary: 'rgba(0,0,0,0.08)',
        text: '#1d1d1f',
        textMuted: '#6e6e73',
        textDimmer: '#8e8e93',
        bgContainer: '#ffffff',
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
    { key: '/', icon: <DashboardOutlined />, label: d.navDashboard },
    { key: '/sequences', icon: <OrderedListOutlined />, label: d.navSequences },
    { key: '/executions', icon: <PlayCircleOutlined />, label: d.navExecutions },
    { key: '/monitoring', icon: <RocketOutlined />,     label: d.navMonitoring },
    { key: '/messages',  icon: <MessageOutlined />,     label: d.navMessages },
    { key: '/timeline',  icon: <RadarChartOutlined />,  label: d.navTimeline },
    { key: '/simulator', icon: <ExperimentOutlined />,  label: d.navSimulator },
    { key: '/demo', icon: <PlaySquareOutlined />, label: d.navDemo },
    ...(isAdmin
      ? [
          { key: '/audit-log', icon: <SafetyCertificateOutlined />, label: d.navAuditLog },
          { key: '/users', icon: <UserOutlined />, label: d.navUsers },
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
        borderRadius: 10,
        boxShadow: isDark ? '0 4px 20px rgba(0,0,0,0.35)' : '0 4px 20px rgba(0,0,0,0.12)',
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
        <Text strong style={{ color: c.text }}>{d.notifTitle}</Text>
        <Text style={{ color: c.textMuted, fontSize: 12 }}>{activeExecutions.length} {d.notifNActive}</Text>
      </div>

      {activeExecutions.length === 0 ? (
        <div style={{ padding: '24px 16px' }}>
          <Empty description={<Text style={{ color: c.textMuted }}>{d.notifEmpty}</Text>} image={Empty.PRESENTED_IMAGE_SIMPLE} />
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
                      color: getExecutionStatusColor(exec.status, isDark),
                      fontWeight: 500,
                    }}
                  >
                    {d.instanceStatuses[exec.status] ?? exec.status}
                  </span>
                </div>
                <Text style={{ color: c.textMuted, fontSize: 12 }}>
                  {d.notifAircraftLabel}: {exec.aircraftId}
                  {exec.flightNumber ? ` · ${d.notifFlightLabel}: ${exec.flightNumber}` : ''}
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
          {d.notifViewAll}
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
              <span className="sidebar-logo-name">{d.sysName}</span>
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
          <Text style={{ fontSize: 11, color: c.textDimmer }}>{d.sysOnline}</Text>
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
            color="#30d158"
            text={
              <Text style={{ fontSize: 12, color: c.textMuted }}>
                {d.sysTagline}
              </Text>
            }
          />

          {/* Right: WS status + theme toggle + notifications + user */}
          <Space size={8}>
            {/* P7-4: WS connection indicator */}
            <ConnectionStatus />
            {/* Theme toggle */}
            <Tooltip title={isDark ? d.themeLight : d.themeDark}>
              <Button
                type="text"
                icon={isDark ? <SunOutlined style={{ fontSize: 16 }} /> : <MoonOutlined style={{ fontSize: 16 }} />}
                onClick={toggleTheme}
                aria-label={isDark ? d.themeLight : d.themeDark}
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
              <Tooltip title={d.notifTitle}>
                <Badge count={activeExecutions.length} size="small" offset={[-2, 2]}>
                  <Button
                    type="text"
                    icon={<BellOutlined style={{ fontSize: 16 }} />}
                    aria-label={d.notifTitle}
                    style={{ color: activeExecutions.length > 0 ? '#ff9f0a' : c.textMuted }}
                  />
                </Badge>
              </Tooltip>
            </Dropdown>

            {/* User info — показываем полное имя, Tooltip как запасной вариант */}
            <Tooltip title={user?.fullName}>
              <div
                role="button"
                tabIndex={0}
                aria-label={d.headerProfileBtn}
                onClick={() => navigate('/profile')}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigate('/profile'); }}
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
                  {d.roles[user?.role ?? ''] ?? user?.role}
                </div>
              </div>
            </Tooltip>

            <Tooltip title={d.headerProfileBtn}>
              <Button
                type="text"
                icon={<ProfileOutlined />}
                onClick={() => navigate('/profile')}
                aria-label={d.headerProfileBtn}
                style={{ color: c.textMuted }}
              />
            </Tooltip>

            <Tooltip title={d.headerLogoutBtn}>
              <Button
                type="text"
                icon={<LogoutOutlined />}
                onClick={logout}
                aria-label={d.headerLogoutBtn}
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
              borderRadius: 10,
              border: `1px solid ${c.borderSecondary}`,
              minHeight: 'calc(100vh - 112px)',
            }}
          >
            <ErrorBoundary>
              <Outlet />
            </ErrorBoundary>
          </div>
        </Content>
      </Layout>
    </Layout>
  );
};
