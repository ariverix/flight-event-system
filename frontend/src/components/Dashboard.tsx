import React, { useState, useEffect } from 'react';
import { Card, Row, Col, Statistic, Spin } from 'antd';
import { OrderedListOutlined, PlayCircleOutlined, MessageOutlined } from '@ant-design/icons';
import { sequenceApi } from '../api/sequenceApi';
import { executionApi } from '../api/executionApi';
import { messageApi } from '../api/messageApi';

export const Dashboard: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState({
    activeSequences: 0,
    runningExecutions: 0,
    todayMessages: 0,
  });

  const loadStats = async () => {
    setLoading(true);
    try {
      const [sequences, executions, messages] = await Promise.all([
        sequenceApi.getSequences(0, 1, 'ACTIVE'),
        executionApi.getExecutions(0, 1, 'RUNNING'),
        messageApi.getMessages(0, 1),
      ]);

      setStats({
        activeSequences: sequences.totalElements,
        runningExecutions: executions.totalElements,
        todayMessages: messages.totalElements,
      });
    } catch (error) {
      console.error('Failed to load dashboard stats:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadStats, 10000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '400px' }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>Dashboard</h2>
      <Row gutter={16}>
        <Col span={8}>
          <Card>
            <Statistic
              title="Active Sequences"
              value={stats.activeSequences}
              prefix={<OrderedListOutlined />}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="Running Executions"
              value={stats.runningExecutions}
              prefix={<PlayCircleOutlined />}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="Total Messages"
              value={stats.todayMessages}
              prefix={<MessageOutlined />}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};
