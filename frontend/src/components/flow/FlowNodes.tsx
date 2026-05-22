import React from 'react';
import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import {
  CheckOutlined, CloseOutlined,
  ClockCircleOutlined, ThunderboltOutlined, QuestionCircleOutlined,
} from '@ant-design/icons';

export type FlowNodeStatus = 'DEFAULT' | 'RUNNING' | 'SUCCESS' | 'FAILURE' | 'WAITING' | 'SKIPPED';
type FlowNodeType = 'ACTION' | 'WAIT' | 'EVALUATE';

export interface FlowNodeData extends Record<string, unknown> {
  label: string;
  stepNumber?: number;
  status?: FlowNodeStatus;
}

const PALETTE: Record<FlowNodeType, Record<FlowNodeStatus, { bg: string; bd: string; cl: string; glow: string }>> = {
  ACTION: {
    DEFAULT: { bg:'rgba(59,130,246,0.09)',  bd:'rgba(59,130,246,0.28)',  cl:'#3b82f6',  glow:'none' },
    RUNNING: { bg:'rgba(59,130,246,0.17)',  bd:'rgba(59,130,246,0.62)',  cl:'#60a5fa',  glow:'0 0 22px rgba(59,130,246,0.40)' },
    SUCCESS: { bg:'rgba(16,185,129,0.11)',  bd:'rgba(16,185,129,0.45)',  cl:'#10b981',  glow:'0 0 18px rgba(16,185,129,0.28)' },
    FAILURE: { bg:'rgba(239,68,68,0.11)',   bd:'rgba(239,68,68,0.40)',   cl:'#ef4444',  glow:'0 0 18px rgba(239,68,68,0.22)' },
    WAITING: { bg:'rgba(59,130,246,0.09)',  bd:'rgba(59,130,246,0.28)',  cl:'#3b82f6',  glow:'none' },
    SKIPPED: { bg:'rgba(255,255,255,0.04)', bd:'rgba(255,255,255,0.12)', cl:'rgba(255,255,255,0.34)', glow:'none' },
  },
  WAIT: {
    DEFAULT: { bg:'rgba(245,158,11,0.09)',  bd:'rgba(245,158,11,0.30)',  cl:'#f59e0b',  glow:'none' },
    RUNNING: { bg:'rgba(245,158,11,0.09)',  bd:'rgba(245,158,11,0.30)',  cl:'#f59e0b',  glow:'none' },
    SUCCESS: { bg:'rgba(16,185,129,0.11)',  bd:'rgba(16,185,129,0.45)',  cl:'#10b981',  glow:'0 0 18px rgba(16,185,129,0.28)' },
    FAILURE: { bg:'rgba(239,68,68,0.11)',   bd:'rgba(239,68,68,0.40)',   cl:'#ef4444',  glow:'0 0 18px rgba(239,68,68,0.22)' },
    WAITING: { bg:'rgba(245,158,11,0.17)',  bd:'rgba(245,158,11,0.60)',  cl:'#fbbf24',  glow:'0 0 26px rgba(245,158,11,0.46)' },
    SKIPPED: { bg:'rgba(255,255,255,0.04)', bd:'rgba(255,255,255,0.12)', cl:'rgba(255,255,255,0.34)', glow:'none' },
  },
  EVALUATE: {
    DEFAULT: { bg:'rgba(139,92,246,0.09)',  bd:'rgba(139,92,246,0.30)',  cl:'#8b5cf6',  glow:'none' },
    RUNNING: { bg:'rgba(139,92,246,0.17)',  bd:'rgba(139,92,246,0.60)',  cl:'#a78bfa',  glow:'0 0 22px rgba(139,92,246,0.36)' },
    SUCCESS: { bg:'rgba(16,185,129,0.11)',  bd:'rgba(16,185,129,0.45)',  cl:'#10b981',  glow:'0 0 18px rgba(16,185,129,0.28)' },
    FAILURE: { bg:'rgba(239,68,68,0.11)',   bd:'rgba(239,68,68,0.40)',   cl:'#ef4444',  glow:'0 0 18px rgba(239,68,68,0.22)' },
    WAITING: { bg:'rgba(139,92,246,0.09)',  bd:'rgba(139,92,246,0.30)',  cl:'#8b5cf6',  glow:'none' },
    SKIPPED: { bg:'rgba(255,255,255,0.04)', bd:'rgba(255,255,255,0.12)', cl:'rgba(255,255,255,0.34)', glow:'none' },
  },
};

const TYPE_LABEL: Record<FlowNodeType, string> = { ACTION: 'ACTION', WAIT: 'WAIT', EVALUATE: 'EVALUATE' };

const StatusIcon: React.FC<{ status: FlowNodeStatus; type: FlowNodeType }> = ({ status, type }) => {
  const s: React.CSSProperties = { fontSize: 11 };
  if (status === 'SUCCESS') return <CheckOutlined style={s} />;
  if (status === 'FAILURE') return <CloseOutlined style={s} />;
  if (status === 'WAITING') return <ClockCircleOutlined style={s} />;
  if (status === 'RUNNING') return <ThunderboltOutlined style={s} />;
  if (type === 'WAIT')      return <ClockCircleOutlined style={{ ...s, opacity: 0.55 }} />;
  if (type === 'EVALUATE')  return <QuestionCircleOutlined style={{ ...s, opacity: 0.55 }} />;
  return <ThunderboltOutlined style={{ ...s, opacity: 0.55 }} />;
};

const BaseFlowNode: React.FC<{ data: FlowNodeData; nodeType: FlowNodeType }> = ({ data, nodeType }) => {
  const status  = (data.status ?? 'DEFAULT') as FlowNodeStatus;
  const palette = PALETTE[nodeType][status] ?? PALETTE[nodeType].DEFAULT;

  return (
    <>
      <Handle type="target" position={Position.Top}
        style={{ background: palette.bd, border: 'none', width: 8, height: 8, top: -4 }} />
      <div style={{
        minWidth: 155, maxWidth: 210,
        padding: '10px 14px',
        borderRadius: 12,
        background: palette.bg,
        border: `1px solid ${palette.bd}`,
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
        boxShadow: palette.glow !== 'none'
          ? `${palette.glow}, inset 0 1px 0 rgba(255,255,255,0.07)`
          : 'inset 0 1px 0 rgba(255,255,255,0.05)',
        animation: status === 'WAITING' ? 'pulseWait 1.5s ease-in-out infinite' : undefined,
        cursor: 'default',
      }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 5,
            fontSize: 10, fontWeight: 700, color: palette.cl,
            letterSpacing: '0.08em', textTransform: 'uppercase',
          }}>
            <StatusIcon status={status} type={nodeType} />
            {TYPE_LABEL[nodeType]}
          </div>
          {data.stepNumber != null && (
            <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.28)', fontWeight: 600 }}>
              #{data.stepNumber}
            </span>
          )}
        </div>
        <div style={{
          fontSize: 12, fontWeight: 500,
          color: 'rgba(255,255,255,0.88)',
          lineHeight: 1.4,
          wordBreak: 'break-word',
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}>
          {data.label}
        </div>
      </div>
      <Handle type="source" position={Position.Bottom}
        style={{ background: palette.bd, border: 'none', width: 8, height: 8, bottom: -4 }} />
    </>
  );
};

export const ActionNode:   React.FC<NodeProps> = ({ data }) => <BaseFlowNode data={data as FlowNodeData} nodeType="ACTION" />;
export const WaitNode:     React.FC<NodeProps> = ({ data }) => <BaseFlowNode data={data as FlowNodeData} nodeType="WAIT" />;
export const EvaluateNode: React.FC<NodeProps> = ({ data }) => <BaseFlowNode data={data as FlowNodeData} nodeType="EVALUATE" />;

export const EndFlowNode: React.FC<NodeProps> = ({ data }) => {
  const d = data as FlowNodeData;
  const ok   = d.status === 'SUCCESS';
  const fail = d.status === 'FAILURE';
  return (
    <>
      <Handle type="target" position={Position.Top}
        style={{ background: ok ? 'rgba(16,185,129,0.6)' : 'rgba(255,255,255,0.22)', border: 'none', width: 8, height: 8, top: -4 }} />
      <div style={{
        padding: '8px 24px', borderRadius: 50,
        background:  ok ? 'rgba(16,185,129,0.13)' : fail ? 'rgba(239,68,68,0.13)' : 'rgba(255,255,255,0.06)',
        border: `1px solid ${ok ? 'rgba(16,185,129,0.50)' : fail ? 'rgba(239,68,68,0.40)' : 'rgba(255,255,255,0.15)'}`,
        color: ok ? '#10b981' : fail ? '#ef4444' : 'rgba(255,255,255,0.52)',
        fontWeight: 700, fontSize: 12,
        backdropFilter: 'blur(10px)',
        display: 'flex', alignItems: 'center', gap: 6,
        boxShadow: ok ? '0 0 20px rgba(16,185,129,0.28)' : 'none',
        whiteSpace: 'nowrap',
      }}>
        {ok && <CheckOutlined style={{ fontSize: 11 }} />}
        {fail && <CloseOutlined style={{ fontSize: 11 }} />}
        {d.label || 'END'}
      </div>
    </>
  );
};

export const flowNodeTypes = {
  action:   ActionNode,
  wait:     WaitNode,
  evaluate: EvaluateNode,
  end:      EndFlowNode,
};
