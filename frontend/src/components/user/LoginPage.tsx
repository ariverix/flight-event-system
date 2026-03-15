import React, { useState } from 'react';
import { Form, Input, Button, notification } from 'antd';
import { UserOutlined, LockOutlined, RocketOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

export const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      await login(values.username, values.password);
      notification.success({
        message: 'Вход выполнен',
        description: 'Добро пожаловать в Систему ЕСА!',
      });
      navigate('/');
    } catch (error: any) {
      notification.error({
        message: 'Ошибка входа',
        description: error.response?.data?.message || 'Неверное имя пользователя или пароль',
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-bg">
      <div className="login-card fade-in-up">
        {/* Logo */}
        <div className="login-logo">
          <div className="login-logo-icon">
            <RocketOutlined style={{ fontSize: 40, color: '#1677ff' }} />
          </div>
          <h1 className="login-title">СИСТЕМА ЕСА</h1>
          <p className="login-subtitle">
            Управление последовательностями событий ВС
          </p>
        </div>

        <Form name="login" onFinish={onFinish} autoComplete="off" size="large">
          <Form.Item
            name="username"
            rules={[{ required: true, message: 'Введите имя пользователя' }]}
          >
            <Input
              prefix={<UserOutlined style={{ color: '#484f58' }} />}
              placeholder="Имя пользователя"
              style={{ background: '#1c2128', border: '1px solid #30363d' }}
            />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: 'Введите пароль' }]}
          >
            <Input.Password
              prefix={<LockOutlined style={{ color: '#484f58' }} />}
              placeholder="Пароль"
              style={{ background: '#1c2128', border: '1px solid #30363d' }}
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              style={{
                height: 44,
                fontSize: 15,
                fontWeight: 600,
                letterSpacing: '0.03em',
              }}
            >
              Войти в систему
            </Button>
          </Form.Item>
        </Form>

        <div
          style={{
            marginTop: 20,
            textAlign: 'center',
            fontSize: 11,
            color: '#484f58',
            borderTop: '1px solid #21262d',
            paddingTop: 16,
          }}
        >
          © 2026 Система ЕСА · Дипломная работа
        </div>
      </div>
    </div>
  );
};
