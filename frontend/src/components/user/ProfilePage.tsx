import React, { useState, useEffect } from 'react';
import { Card, Descriptions, Tag, Spin, notification, Avatar, Typography } from 'antd';
import { UserOutlined, SafetyOutlined, CalendarOutlined } from '@ant-design/icons';
import { authApi } from '../../api/authApi';
import { UserResponse } from '../../types/auth';

const { Title, Text } = Typography;

const ROLE_LABEL: Record<string, string> = {
  ADMIN:    'Администратор',
  OPERATOR: 'Оператор',
};

const ROLE_COLOR: Record<string, string> = {
  ADMIN:    'red',
  OPERATOR: 'blue',
};

export const ProfilePage: React.FC = () => {
  const [profile, setProfile] = useState<UserResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authApi.me()
      .then(setProfile)
      .catch((error: any) => {
        notification.error({
          message: 'Ошибка загрузки профиля',
          description: error.response?.data?.message || error.message,
        });
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 400 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!profile) return null;

  return (
    <div className="fade-in-up" style={{ maxWidth: 640 }}>
      <h2 className="page-title" style={{ marginBottom: 24 }}>Профиль пользователя</h2>

      {/* Avatar card */}
      <Card style={{ marginBottom: 16, borderColor: '#21262d' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
          <Avatar
            size={72}
            icon={<UserOutlined />}
            style={{
              background: 'linear-gradient(135deg, #1677ff 0%, #0950b3 100%)',
              flexShrink: 0,
            }}
          />
          <div>
            <Title level={4} style={{ margin: 0, color: '#e6edf3' }}>{profile.fullName}</Title>
            <Text style={{ color: '#848d97' }}>@{profile.username}</Text>
            <div style={{ marginTop: 6 }}>
              <Tag color={ROLE_COLOR[profile.role]}>{ROLE_LABEL[profile.role] ?? profile.role}</Tag>
              <Tag color={profile.enabled ? 'green' : 'default'}>
                {profile.enabled ? 'Активен' : 'Отключён'}
              </Tag>
            </div>
          </div>
        </div>
      </Card>

      {/* Details */}
      <Card
        title={<span style={{ color: '#e6edf3' }}>Учётные данные</span>}
        style={{ borderColor: '#21262d' }}
      >
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item
            label={<span><UserOutlined style={{ marginRight: 6 }} />Имя пользователя</span>}
          >
            {profile.username}
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><UserOutlined style={{ marginRight: 6 }} />Полное имя</span>}
          >
            {profile.fullName}
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><SafetyOutlined style={{ marginRight: 6 }} />Роль в системе</span>}
          >
            <Tag color={ROLE_COLOR[profile.role]}>{ROLE_LABEL[profile.role] ?? profile.role}</Tag>
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><CalendarOutlined style={{ marginRight: 6 }} />Зарегистрирован</span>}
          >
            {new Date(profile.createdAt).toLocaleString('ru-RU')}
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
};
