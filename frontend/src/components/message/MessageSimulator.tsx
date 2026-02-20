import React, { useState } from 'react';
import { Card, Form, Input, Select, Button, Space, notification, Divider, Radio } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { messageApi } from '../../api/messageApi';

export const MessageSimulator: React.FC = () => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [simulationType, setSimulationType] = useState<'message' | 'stage'>('message');

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
      notification.success({
        message: 'Message sent successfully',
      });
      form.resetFields(['templateName', 'metadataJson']);
    } catch (error: any) {
      notification.error({
        message: 'Failed to send message',
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
      notification.success({
        message: 'Flight stage changed successfully',
      });
      form.resetFields(['newStage']);
    } catch (error: any) {
      notification.error({
        message: 'Failed to change flight stage',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Message Simulator</h2>

      <Card>
        <Radio.Group
          value={simulationType}
          onChange={(e) => {
            setSimulationType(e.target.value);
            form.resetFields();
          }}
          style={{ marginBottom: 24 }}
        >
          <Radio.Button value="message">Send Message</Radio.Button>
          <Radio.Button value="stage">Change Flight Stage</Radio.Button>
        </Radio.Group>

        {simulationType === 'message' ? (
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSendMessage}
            initialValues={{ messageType: 'DOWNLINK' }}
          >
            <Form.Item
              name="messageType"
              label="Message Type"
              rules={[{ required: true, message: 'Please select message type!' }]}
            >
              <Select>
                <Select.Option value="DOWNLINK">DOWNLINK</Select.Option>
                <Select.Option value="UPLINK">UPLINK</Select.Option>
                <Select.Option value="GROUND">GROUND</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item
              name="templateName"
              label="Template Name"
              rules={[{ required: true, message: 'Please input template name!' }]}
            >
              <Input placeholder="e.g., POSITION_REPORT, WEATHER_UPDATE" />
            </Form.Item>

            <Form.Item
              name="aircraftId"
              label="Aircraft ID"
              rules={[{ required: true, message: 'Please input aircraft ID!' }]}
            >
              <Input placeholder="e.g., SU9876" />
            </Form.Item>

            <Form.Item name="flightNumber" label="Flight Number">
              <Input placeholder="e.g., AFL123 (optional)" />
            </Form.Item>

            <Form.Item name="metadataJson" label="Metadata (JSON)">
              <Input.TextArea
                rows={4}
                placeholder='{"latitude": 55.7558, "longitude": 37.6173}'
              />
            </Form.Item>

            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                Send Message
              </Button>
            </Form.Item>
          </Form>
        ) : (
          <Form
            form={form}
            layout="vertical"
            onFinish={handleStageChange}
          >
            <Form.Item
              name="aircraftId"
              label="Aircraft ID"
              rules={[{ required: true, message: 'Please input aircraft ID!' }]}
            >
              <Input placeholder="e.g., SU9876" />
            </Form.Item>

            <Form.Item name="flightNumber" label="Flight Number">
              <Input placeholder="e.g., AFL123 (optional)" />
            </Form.Item>

            <Form.Item
              name="newStage"
              label="New Flight Stage"
              rules={[{ required: true, message: 'Please select flight stage!' }]}
            >
              <Select>
                <Select.Option value="INIT">INIT - Initial</Select.Option>
                <Select.Option value="OUT">OUT - Taxi Out</Select.Option>
                <Select.Option value="OFF">OFF - Takeoff</Select.Option>
                <Select.Option value="ON">ON - Landing</Select.Option>
                <Select.Option value="IN">IN - Taxi In</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                Change Stage
              </Button>
            </Form.Item>
          </Form>
        )}
      </Card>

      <Card title="Quick Test Scenarios" style={{ marginTop: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>
            <strong>Scenario 1: Position Report</strong>
            <br />
            Message: DOWNLINK / POSITION_REPORT / SU9876
            <br />
            Metadata: {`{"latitude": 55.7558, "longitude": 37.6173}`}
          </div>
          <Divider />
          <div>
            <strong>Scenario 2: Flight Stage Progression</strong>
            <br />
            INIT → OUT → OFF → ON → IN
          </div>
          <Divider />
          <div>
            <strong>Scenario 3: Weather Update</strong>
            <br />
            Message: GROUND / WEATHER_UPDATE / SU9876
            <br />
            Metadata: {`{"temperature": -5, "wind": "10kt"}`}
          </div>
        </Space>
      </Card>
    </div>
  );
};
