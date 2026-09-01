import React, { useState, useEffect } from 'react';
import { Card, Descriptions, Tag, Skeleton, Avatar, Typography } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import { UserOutlined, SafetyOutlined, CalendarOutlined } from '@ant-design/icons';
import { authApi } from '../../api/authApi';
import { UserResponse } from '../../types/auth';
import { useEditorI18n } from '../../i18n/useEditorI18n';

const { Title, Text } = Typography;

const ROLE_COLOR: Record<string, string> = {
  ADMIN:    'error',
  OPERATOR: 'processing',
};

export const ProfilePage: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
  const [profile, setProfile] = useState<UserResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authApi.me()
      .then(setProfile)
      .catch((error: any) => {
        notification.error({
          message: d.profileLoadError,
          description: error.response?.data?.message || error.message,
        });
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div style={{ maxWidth: 640 }}>
        <Skeleton active avatar paragraph={{ rows: 4 }} />
      </div>
    );
  }

  if (!profile) return null;

  return (
    <div className="fade-in-up" style={{ maxWidth: 640 }}>
      <h2 className="page-title" style={{ marginBottom: 24 }}>{d.headerProfileBtn}</h2>

      <Card style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
          <Avatar
            size={72}
            icon={<UserOutlined />}
            style={{
              background: 'linear-gradient(135deg, var(--accent-blue) 0%, var(--accent-indigo) 100%)',
              flexShrink: 0,
              boxShadow: 'var(--glow-blue)',
            }}
          />
          <div>
            <Title level={4} style={{ margin: 0, color: 'var(--text-1)' }}>{profile.fullName}</Title>
            <Text style={{ color: 'var(--text-3)' }}>@{profile.username}</Text>
            <div style={{ marginTop: 6, display: 'flex', gap: 6 }}>
              <Tag color={ROLE_COLOR[profile.role]}>{d.roles[profile.role] ?? profile.role}</Tag>
              <Tag color={profile.enabled ? 'success' : 'default'}>
                {profile.enabled ? d.profileStatusActive : d.profileStatusDisabled}
              </Tag>
            </div>
          </div>
        </div>
      </Card>

      <Card title={<span style={{ color: 'var(--text-1)' }}>{d.profileCredentialsCard}</span>}>
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item
            label={<span><UserOutlined style={{ marginRight: 6 }} />{d.profileUsernameLabel}</span>}
          >
            <Text style={{ fontFamily: 'monospace' }}>{profile.username}</Text>
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><UserOutlined style={{ marginRight: 6 }} />{d.profileFullNameLabel}</span>}
          >
            {profile.fullName}
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><SafetyOutlined style={{ marginRight: 6 }} />{d.profileRoleLabel}</span>}
          >
            <Tag color={ROLE_COLOR[profile.role]}>{d.roles[profile.role] ?? profile.role}</Tag>
          </Descriptions.Item>
          <Descriptions.Item
            label={<span><CalendarOutlined style={{ marginRight: 6 }} />{d.profileRegisteredLabel}</span>}
          >
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              {new Date(profile.createdAt).toLocaleString('ru-RU')}
            </span>
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
};
