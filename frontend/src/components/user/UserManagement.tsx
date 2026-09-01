import React, { useState, useEffect, useCallback } from 'react';
import { Table, Button, Form, Input, Select, Modal, Space, Tag, Switch, Tooltip } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import { useAuth } from '../../hooks/useAuth';
import { UserAddOutlined, InboxOutlined } from '@ant-design/icons';
import { authApi } from '../../api/authApi';
import { UserResponse, RegisterRequest } from '../../types/auth';
import { useEditorI18n } from '../../i18n/useEditorI18n';

export const UserManagement: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
  const { user: currentUser } = useAuth();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [form] = Form.useForm();

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const data = await authApi.getUsers();
      setUsers(data);
    } catch (error: any) {
      notification.error({
        message: d.usersLoadError,
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const handleToggleUser = async (userId: number) => {
    try {
      await authApi.toggleUser(userId);
      notification.success({ message: d.usersToggleSuccess });
      loadUsers();
    } catch (error: any) {
      notification.error({
        message: d.usersToggleError,
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleCreateUser = async (values: RegisterRequest) => {
    try {
      await authApi.register(values);
      notification.success({ message: d.usersCreateSuccess });
      form.resetFields();
      setIsModalOpen(false);
      loadUsers();
    } catch (error: any) {
      notification.error({
        message: d.usersCreateError,
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const columns = [
    { title: d.usersColId, dataIndex: 'id', key: 'id', width: 70 },
    { title: d.usersLoginLabel, dataIndex: 'username', key: 'username' },
    { title: d.profileFullNameLabel, dataIndex: 'fullName', key: 'fullName' },
    {
      title: d.usersColRole,
      dataIndex: 'role',
      key: 'role',
      render: (role: string) => (
        <Tag color={role === 'ADMIN' ? 'red' : 'blue'}>{d.roles[role] ?? role}</Tag>
      ),
    },
    {
      title: d.usersColStatus,
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>{enabled ? d.profileStatusActive : d.profileStatusDisabled}</Tag>
      ),
    },
    {
      title: d.profileRegisteredLabel,
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
    {
      title: d.usersColActions,
      key: 'actions',
      render: (_: any, record: UserResponse) => {
        const isSelf = record.username === currentUser?.username;
        const switchEl = (
          <Switch
            checked={record.enabled}
            onChange={() => handleToggleUser(record.id)}
            checkedChildren={d.usersSwitchOn}
            unCheckedChildren={d.usersSwitchOff}
            disabled={isSelf}
          />
        );
        return isSelf
          ? <Tooltip title={d.usersSelfToggleTooltip}>{switchEl}</Tooltip>
          : switchEl;
      },
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">{d.usersPageTitle}</h2>
        <Button
          type="primary"
          icon={<UserAddOutlined />}
          onClick={() => setIsModalOpen(true)}
        >
          {d.usersAddBtn}
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={users}
        loading={loading}
        rowKey="id"
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: (
            <div style={{ padding: '40px 0', textAlign: 'center' }}>
              <InboxOutlined style={{ fontSize: 40, color: 'var(--text-4)', marginBottom: 12, display: 'block' }} />
              <div style={{ color: 'var(--text-3)', fontSize: 14 }}>{d.usersEmptyText}</div>
            </div>
          ),
        }}
        pagination={{
          pageSize: 10,
          showTotal: (total, range) => `${range[0]}–${range[1]} ${d.paginationOf} ${total}`,
        }}
      />

      {!loading && users.length > 0 && (
        <div className="users-stats-row">
          <div className="users-stat-item">
            <span className="users-stat-num">{users.length}</span>
            <span className="users-stat-label">{d.usersStatTotal}</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num green">
              {users.filter((u: UserResponse) => u.enabled).length}
            </span>
            <span className="users-stat-label">{d.usersStatActive}</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num red">
              {users.filter((u: UserResponse) => !u.enabled).length}
            </span>
            <span className="users-stat-label">{d.usersStatDisabled}</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num blue">
              {users.filter((u: UserResponse) => u.role === 'ADMIN').length}
            </span>
            <span className="users-stat-label">{d.usersStatAdmins}</span>
          </div>
        </div>
      )}

      <Modal
        title={d.usersModalTitle}
        open={isModalOpen}
        onCancel={() => {
          setIsModalOpen(false);
          form.resetFields();
        }}
        footer={null}
      >
        <Form form={form} layout="vertical" onFinish={handleCreateUser}>
          <Form.Item
            name="username"
            label={d.usersLoginLabel}
            rules={[{ required: true, message: d.usersLoginRequired }]}
          >
            <Input />
          </Form.Item>

          <Form.Item
            name="password"
            label={d.loginPasswordPlaceholder}
            rules={[
              { required: true, message: d.loginPasswordRequired },
              { min: 6, message: d.usersPasswordMinLength },
            ]}
          >
            <Input.Password />
          </Form.Item>

          <Form.Item
            name="fullName"
            label={d.profileFullNameLabel}
            rules={[{ required: true, message: d.usersFullNameRequired }]}
          >
            <Input />
          </Form.Item>

          <Form.Item
            name="role"
            label={d.usersColRole}
            rules={[{ required: true, message: d.usersRoleRequired }]}
          >
            <Select>
              <Select.Option value="OPERATOR">{d.roles.OPERATOR}</Select.Option>
              <Select.Option value="ADMIN">{d.roles.ADMIN}</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                {d.usersCreateBtn}
              </Button>
              <Button
                onClick={() => {
                  setIsModalOpen(false);
                  form.resetFields();
                }}
              >
                {d.usersCancelBtn}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
