import React from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';

export interface StepNodeData {
  label: string;
  stepType: 'ACTION' | 'EVALUATE' | 'WAIT';
  configLabel: string;
  orderIndex: number;
  state: 'idle' | 'active' | 'success' | 'failure' | 'unreached';
  [key: string]: unknown;
}

const TYPE_ICON: Record<string, string> = {
  ACTION:   '⚡',
  EVALUATE: '🔍',
  WAIT:     '⏳',
};

const TYPE_LABEL: Record<string, string> = {
  ACTION:   'ACTION',
  EVALUATE: 'EVALUATE',
  WAIT:     'WAIT',
};

const STATE_STYLES: Record<string, React.CSSProperties> = {
  idle: {
    background: 'linear-gradient(135deg, #1c2128 0%, #161b22 100%)',
    border: '1.5px solid #30363d',
    color: '#e6edf3',
    boxShadow: '0 2px 8px rgba(0,0,0,0.4)',
  },
  active: {
    background: 'linear-gradient(135deg, #3d2b00 0%, #2d1f00 100%)',
    border: '2px solid #faad14',
    color: '#fff',
    boxShadow: '0 0 0 0 rgba(250,173,20,0.7)',
    animation: 'nodeGlow 1.8s ease-in-out infinite',
  },
  success: {
    background: 'linear-gradient(135deg, #0d2a1a 0%, #071a10 100%)',
    border: '2px solid #3fb950',
    color: '#3fb950',
    boxShadow: '0 2px 12px rgba(63,185,80,0.25)',
  },
  failure: {
    background: 'linear-gradient(135deg, #2a0d0d 0%, #1a0707 100%)',
    border: '2px solid #f85149',
    color: '#f85149',
    boxShadow: '0 2px 12px rgba(248,81,73,0.25)',
  },
  unreached: {
    background: 'linear-gradient(135deg, #161b22 0%, #0d1117 100%)',
    border: '1.5px solid #21262d',
    color: '#484f58',
    boxShadow: 'none',
    opacity: 0.5,
  },
};

const TYPE_ACCENT: Record<string, string> = {
  ACTION:   '#1677ff',
  EVALUATE: '#faad14',
  WAIT:     '#7c3aed',
};

export const CustomStepNode: React.FC<NodeProps> = ({ data }) => {
  const d = data as StepNodeData;
  const style = STATE_STYLES[d.state] ?? STATE_STYLES.idle;
  const accent = TYPE_ACCENT[d.stepType] ?? '#888';
  const icon = TYPE_ICON[d.stepType] ?? '●';
  const isActive = d.state === 'active';
  const isSuccess = d.state === 'success';
  const isFailure = d.state === 'failure';

  return (
    <>
      <Handle
        type="target"
        position={Position.Top}
        style={{ background: '#30363d', border: '1px solid #484f58', width: 8, height: 8 }}
      />

      <div
        style={{
          ...style,
          borderRadius: 10,
          padding: '10px 14px',
          minWidth: 160,
          maxWidth: 200,
          fontFamily: "'Inter', sans-serif",
          position: 'relative',
          overflow: 'hidden',
          transition: 'box-shadow 0.3s ease, border-color 0.3s ease',
        }}
      >
        {/* Accent bar top */}
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, height: 3,
          background: isSuccess ? '#3fb950' : isFailure ? '#f85149' : isActive ? '#faad14' : accent,
          borderRadius: '10px 10px 0 0',
          transition: 'background 0.3s ease',
        }} />

        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <div style={{
            width: 22, height: 22, borderRadius: 6,
            background: isSuccess ? 'rgba(63,185,80,0.15)' : isFailure ? 'rgba(248,81,73,0.15)' : isActive ? 'rgba(250,173,20,0.15)' : `${accent}22`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 12, flexShrink: 0,
            border: `1px solid ${isSuccess ? 'rgba(63,185,80,0.3)' : isFailure ? 'rgba(248,81,73,0.3)' : isActive ? 'rgba(250,173,20,0.3)' : `${accent}44`}`,
          }}>
            {isSuccess ? '✓' : isFailure ? '✗' : isActive ? '▶' : icon}
          </div>
          <div style={{ fontSize: 9, fontWeight: 700, letterSpacing: '0.08em', color: isSuccess ? '#3fb950' : isFailure ? '#f85149' : isActive ? '#faad14' : accent, textTransform: 'uppercase' }}>
            {TYPE_LABEL[d.stepType]}
          </div>
          <div style={{ marginLeft: 'auto', fontSize: 9, color: '#484f58', fontWeight: 600 }}>
            #{d.orderIndex}
          </div>
        </div>

        {/* Config label */}
        <div style={{
          fontSize: 11, fontWeight: 600, color: isSuccess ? '#3fb950' : isFailure ? '#f85149' : isActive ? '#faad14' : '#8b949e',
          lineHeight: 1.4, wordBreak: 'break-word',
        }}>
          {d.configLabel || '—'}
        </div>

        {/* State indicator dot */}
        {(isSuccess || isFailure || isActive) && (
          <div style={{
            position: 'absolute', bottom: 6, right: 8,
            width: 6, height: 6, borderRadius: '50%',
            background: isSuccess ? '#3fb950' : isFailure ? '#f85149' : '#faad14',
          }} />
        )}
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
        style={{ background: '#30363d', border: '1px solid #484f58', width: 8, height: 8 }}
      />
    </>
  );
};

export const CustomEndNode: React.FC<NodeProps> = ({ data }) => {
  const d = data as { label: string; reached?: boolean };
  const reached = d.reached ?? false;

  return (
    <>
      <Handle
        type="target"
        position={Position.Top}
        style={{ background: '#30363d', border: '1px solid #484f58', width: 8, height: 8 }}
      />
      <div style={{
        background: reached
          ? 'linear-gradient(135deg, #0d2a1a 0%, #071a10 100%)'
          : 'linear-gradient(135deg, #1c2128 0%, #161b22 100%)',
        border: reached ? '2px solid #3fb950' : '1.5px solid #30363d',
        borderRadius: 20,
        padding: '6px 16px',
        color: reached ? '#3fb950' : '#484f58',
        fontWeight: 700,
        fontSize: 11,
        letterSpacing: '0.08em',
        boxShadow: reached ? '0 2px 10px rgba(63,185,80,0.2)' : 'none',
        transition: 'all 0.3s ease',
      }}>
        ● {d.label}
      </div>
    </>
  );
};
