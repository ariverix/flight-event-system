import React, { useState } from 'react';
import { Card, Form, Input, Select, Button, Space, notification, Divider, Radio, Tag, Typography, Row, Col } from 'antd';
import { SendOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { messageApi } from '../../api/messageApi';
import { useTheme } from '../../context/ThemeContext';

const { Text } = Typography;

export const MessageSimulator: React.FC = () => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [simulationType, setSimulationType] = useState<'message' | 'stage'>('message');
  const { isDark } = useTheme();

  const c = isDark
    ? { borderSecondary: '#21262d', text: '#e6edf3', textMuted: '#848d97', textDimmer: '#484f58', bgElevated: '#1c2128' }
    : { borderSecondary: '#d8dee4', text: '#1f2328', textMuted: '#636c76', textDimmer: '#9da3ab', bgElevated: '#f6f8fa' };

  const handleSendMessage = async (values: any) => {
    setLoading(true);
    try {
      await messageApi.sendMessage({
        messageType: values.messageType,
        templateName: values.templateName,
        aircraftId: values.aircraftId,
        flightNumber: values.flightNumber || undefined,
        metadataJson: values.metadataJson || undefined,
      });
      notification.success({ message: 'Сообщение отправлено успешно' });
      form.resetFields(['templateName', 'metadataJson']);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка отправки сообщения',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  const handleStageChange = async (values: any) => {
    setLoading(true);
    try {
      await messageApi.changeFlightStage({
        aircraftId: values.aircraftId,
        flightNumber: values.flightNumber || undefined,
        newStage: values.newStage,
      });
      notification.success({ message: 'Фаза полёта изменена успешно' });
      form.resetFields(['newStage']);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка смены фазы полёта',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Симулятор событий</h2>
      </div>

      <Card style={{ borderColor: c.borderSecondary }}>
        <Radio.Group
          value={simulationType}
          onChange={(e) => {
            setSimulationType(e.target.value);
            form.resetFields();
          }}
          style={{ marginBottom: 20 }}
          buttonStyle="solid"
        >
          <Radio.Button value="message">
            <SendOutlined style={{ marginRight: 6 }} />
            Отправить сообщение
          </Radio.Button>
          <Radio.Button value="stage">
            <ThunderboltOutlined style={{ marginRight: 6 }} />
            Изменить фазу полёта
          </Radio.Button>
        </Radio.Group>

        {simulationType === 'message' ? (
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSendMessage}
            initialValues={{ messageType: 'DOWNLINK' }}
          >
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="messageType"
                  label="Тип сообщения"
                  rules={[{ required: true, message: 'Выберите тип' }]}
                >
                  <Select>
                    <Select.Option value="DOWNLINK">
                      <Tag color="blue" style={{ marginRight: 4 }}>DOWNLINK</Tag>
                      Борт → земля
                    </Select.Option>
                    <Select.Option value="UPLINK">
                      <Tag color="green" style={{ marginRight: 4 }}>UPLINK</Tag>
                      Земля → борт
                    </Select.Option>
                    <Select.Option value="GROUND">
                      <Tag color="orange" style={{ marginRight: 4 }}>GROUND</Tag>
                      Наземная
                    </Select.Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="templateName"
                  label="Шаблон сообщения"
                  rules={[{ required: true, message: 'Введите шаблон' }]}
                >
                  <Input placeholder="POSITION_REPORT, WEATHER_UPDATE…" />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="aircraftId"
                  label="Идентификатор ВС"
                  rules={[{ required: true, message: 'Введите идентификатор ВС' }]}
                >
                  <Input placeholder="VP-BQR" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="flightNumber" label="Номер рейса (необязательно)">
                  <Input placeholder="SU1234" />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item name="metadataJson" label="Метаданные (JSON, необязательно)">
              <Input.TextArea
                rows={3}
                placeholder='{"latitude": 55.7558, "longitude": 37.6173}'
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                Отправить сообщение
              </Button>
            </Form.Item>
          </Form>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleStageChange}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="aircraftId"
                  label="Идентификатор ВС"
                  rules={[{ required: true, message: 'Введите идентификатор ВС' }]}
                >
                  <Input placeholder="VP-BQR" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="flightNumber" label="Номер рейса (необязательно)">
                  <Input placeholder="SU1234" />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              name="newStage"
              label="Новая фаза полёта"
              rules={[{ required: true, message: 'Выберите фазу полёта' }]}
            >
              <Select>
                <Select.Option value="INIT">INIT — Начальная</Select.Option>
                <Select.Option value="OUT">OUT — Выруливание на ВПП</Select.Option>
                <Select.Option value="OFF">OFF — Взлёт</Select.Option>
                <Select.Option value="ON">ON — Посадка</Select.Option>
                <Select.Option value="IN">IN — Заруливание</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" icon={<ThunderboltOutlined />} loading={loading}>
                Изменить фазу
              </Button>
            </Form.Item>
          </Form>
        )}
      </Card>

      <Card
        title={<span style={{ color: c.text }}>Тестовые сценарии</span>}
        style={{ marginTop: 16, borderColor: c.borderSecondary }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={4}>
          <div style={{ padding: '12px 16px', borderRadius: 8, background: c.bgElevated, border: `1px solid ${c.borderSecondary}` }}>
            <Text strong style={{ color: '#1677ff' }}>Сценарий 1: Доклад о местоположении</Text>
            <br />
            <Text style={{ color: c.textMuted, fontSize: 13 }}>
              Тип: DOWNLINK · Шаблон: POSITION_REPORT · ВС: VP-BQR · Рейс: SU1234
            </Text>
            <br />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>
              {'{"latitude": 55.7558, "longitude": 37.6173}'}
            </Text>
          </div>

          <Divider style={{ margin: '8px 0', borderColor: c.borderSecondary }} />

          <div style={{ padding: '12px 16px', borderRadius: 8, background: c.bgElevated, border: `1px solid ${c.borderSecondary}` }}>
            <Text strong style={{ color: '#00c853' }}>Сценарий 2: Прогрессия фаз полёта (демо V9)</Text>
            <br />
            <Text style={{ color: c.textMuted, fontSize: 13 }}>
              INIT → OUT → <strong>OFF</strong> → ON → IN · ВС: VP-BQR · Рейс: SU1234
            </Text>
            <br />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>
              При фазе OFF запускается «Запрос позиционного отчёта после взлёта»
            </Text>
          </div>

          <Divider style={{ margin: '8px 0', borderColor: c.borderSecondary }} />

          <div style={{ padding: '12px 16px', borderRadius: 8, background: c.bgElevated, border: `1px solid ${c.borderSecondary}` }}>
            <Text strong style={{ color: '#faad14' }}>Сценарий 3: Метеосводка</Text>
            <br />
            <Text style={{ color: c.textMuted, fontSize: 13 }}>
              Тип: GROUND · Шаблон: WEATHER_UPDATE · ВС: VP-BQR · Рейс: SU1234
            </Text>
            <br />
            <Text style={{ color: c.textDimmer, fontSize: 12 }}>
              {'{"temperature": -5, "wind": "10kt"}'}
            </Text>
          </div>
        </Space>
      </Card>
    </div>
  );
};
