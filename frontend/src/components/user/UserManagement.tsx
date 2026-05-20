import React, { useState, useEffect } from 'react';
import { Table, Button, Form, Input, Select, Modal, notification, Space, Tag, Switch } from 'antd';
import { UserAddOutlined } from '@ant-design/icons';
import { authApi } from '../../api/authApi';
import { UserResponse, RegisterRequest } from '../../types/auth';

const ROLE_LABEL: Record<string, string> = {
  ADMIN:    'Администратор',
  OPERATOR: 'Оператор',
};

export const UserManagement: React.FC = () => {
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [form] = Form.useForm();

  const loadUsers = async () => {
    setLoading(true);
    try {
      const data = await authApi.getUsers();
      setUsers(data);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка загрузки пользователей',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadUsers(); }, []);

  const handleToggleUser = async (userId: number) => {
    try {
      await authApi.toggleUser(userId);
      notification.success({ message: 'Статус пользователя обновлён' });
      loadUsers();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка обновления статуса',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleCreateUser = async (values: RegisterRequest) => {
    try {
      await authApi.register(values);
      notification.success({ message: 'Пользователь создан успешно' });
      form.resetFields();
      setIsModalOpen(false);
      loadUsers();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка создания пользователя',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    { title: 'Логин', dataIndex: 'username', key: 'username' },
    { title: 'Полное имя', dataIndex: 'fullName', key: 'fullName' },
    {
      title: 'Роль',
      dataIndex: 'role',
      key: 'role',
      render: (role: string) => (
        <Tag color={role === 'ADMIN' ? 'red' : 'blue'}>{ROLE_LABEL[role] ?? role}</Tag>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>{enabled ? 'Активен' : 'Отключён'}</Tag>
      ),
    },
    {
      title: 'Зарегистрирован',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleString('ru-RU'),
    },
    {
      title: 'Активность',
      key: 'actions',
      render: (_: any, record: UserResponse) => (
        <Switch
          checked={record.enabled}
          onChange={() => handleToggleUser(record.id)}
          checkedChildren="Вкл"
          unCheckedChildren="Выкл"
        />
      ),
    },
  ];

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Управление пользователями</h2>
        <Button
          type="primary"
          icon={<UserAddOutlined />}
          onClick={() => setIsModalOpen(true)}
        >
          Добавить пользователя
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={users}
        loading={loading}
        rowKey="id"
        pagination={{
          pageSize: 10,
          showTotal: (total, range) => `${range[0]}–${range[1]} из ${total}`,
        }}
      />

      {!loading && users.length > 0 && (
        <div className="users-stats-row">
          <div className="users-stat-item">
            <span className="users-stat-num">{users.length}</span>
            <span className="users-stat-label">Всего пользователей</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num green">
              {users.filter((u: UserResponse) => u.enabled).length}
            </span>
            <span className="users-stat-label">Активных</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num red">
              {users.filter((u: UserResponse) => !u.enabled).length}
            </span>
            <span className="users-stat-label">Отключённых</span>
          </div>
          <div className="users-stat-item">
            <span className="users-stat-num blue">
              {users.filter((u: UserResponse) => u.role === 'ADMIN').length}
            </span>
            <span className="users-stat-label">Администраторов</span>
          </div>
        </div>
      )}

      <Modal
        title="Регистрация нового пользователя"
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
            label="Логин"
            rules={[{ required: true, message: 'Введите логин' }]}
          >
            <Input />
          </Form.Item>

          <Form.Item
            name="password"
            label="Пароль"
            rules={[
              { required: true, message: 'Введите пароль' },
              { min: 6, message: 'Пароль должен содержать не менее 6 символов' },
            ]}
          >
            <Input.Password />
          </Form.Item>

          <Form.Item
            name="fullName"
            label="Полное имя"
            rules={[{ required: true, message: 'Введите полное имя' }]}
          >
            <Input />
          </Form.Item>

          <Form.Item
            name="role"
            label="Роль"
            rules={[{ required: true, message: 'Выберите роль' }]}
          >
            <Select>
              <Select.Option value="OPERATOR">Оператор</Select.Option>
              <Select.Option value="ADMIN">Администратор</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                Создать пользователя
              </Button>
              <Button
                onClick={() => {
                  setIsModalOpen(false);
                  form.resetFields();
                }}
              >
                Отмена
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
