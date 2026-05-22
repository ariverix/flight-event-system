import React from 'react';
import { Button } from 'antd';
import { InboxOutlined } from '@ant-design/icons';

interface EmptyStateProps {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}

export const EmptyState: React.FC<EmptyStateProps> = ({ icon, title, description, action }) => (
  <div style={{
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '56px 24px',
    textAlign: 'center',
  }}>
    <div style={{
      fontSize: 52,
      color: 'rgba(255,255,255,0.20)',
      marginBottom: 18,
      animation: 'fadeIn 0.5s ease',
    }}>
      {icon ?? <InboxOutlined />}
    </div>
    <div style={{
      fontSize: 16,
      fontWeight: 600,
      color: 'rgba(255,255,255,0.52)',
      marginBottom: 8,
    }}>
      {title}
    </div>
    {description && (
      <div style={{
        fontSize: 13,
        color: 'rgba(255,255,255,0.30)',
        marginBottom: action ? 20 : 0,
        maxWidth: 320,
        lineHeight: 1.6,
      }}>
        {description}
      </div>
    )}
    {action && (
      <Button type="primary" onClick={action.onClick} style={{ borderRadius: 10 }}>
        {action.label}
      </Button>
    )}
  </div>
);
