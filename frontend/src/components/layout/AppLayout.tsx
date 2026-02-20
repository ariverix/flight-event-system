import React from 'react';
import { Layout, Menu, Button, Space, Typography } from 'antd';
import {
  DashboardOutlined,
  OrderedListOutlined,
  PlayCircleOutlined,
  MessageOutlined,
  UserOutlined,
  LogoutOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

export const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, isAdmin } = useAuth();

  const menuItems = [
    {
      key: '/',
      icon: <DashboardOutlined />,
      label: 'Dashboard',
    },
    {
      key: '/sequences',
      icon: <OrderedListOutlined />,
      label: 'Sequences',
    },
    {
      key: '/executions',
      icon: <PlayCircleOutlined />,
      label: 'Executions',
    },
    {
      key: '/messages',
      icon: <MessageOutlined />,
      label: 'Message Log',
    },
    {
      key: '/simulator',
      icon: <ExperimentOutlined />,
      label: 'Simulator',
    },
    ...(isAdmin
      ? [
          {
            key: '/users',
            icon: <UserOutlined />,
            label: 'Users',
          },
        ]
      : []),
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        breakpoint="lg"
        collapsedWidth="0"
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
        }}
      >
        <div
          style={{
            height: '64px',
            margin: '16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'white',
            fontSize: '18px',
            fontWeight: 'bold',
          }}
        >
          ECA System
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout style={{ marginLeft: 200 }}>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
          <Space>
            <Text strong>{user?.fullName}</Text>
            <Text type="secondary">({user?.role})</Text>
            <Button type="text" icon={<LogoutOutlined />} onClick={logout}>
              Logout
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: '24px 16px 0', overflow: 'initial' }}>
          <div style={{ padding: 24, background: '#fff', minHeight: 360 }}>
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
};
