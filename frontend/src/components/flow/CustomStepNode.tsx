import React from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';
import { useTheme } from '../../context/ThemeContext';

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

const TYPE_ACCENT: Record<string, string> = {
  ACTION:   '#1677ff',
  EVALUATE: '#d48806',
  WAIT:     '#7c3aed',
};

interface StateTokens {
  bg: string;
  border: string;
  textMain: string;
  textSub: string;
  shadow: string;
  animation?: string;
  opacity?: number;
}

function getStateTokens(state: StepNodeData['state'], isDark: boolean): StateTokens {
  if (isDark) {
    switch (state) {
      case 'active':    return { bg: 'linear-gradient(135deg,#3d2b00,#2d1f00)', border: '#faad14', textMain: '#faad14', textSub: '#d4a017', shadow: '0 0 0 0 rgba(250,173,20,.7)', animation: 'nodeGlow 1.8s ease-in-out infinite' };
      case 'success':   return { bg: 'linear-gradient(135deg,#0d2a1a,#071a10)', border: '#3fb950', textMain: '#3fb950', textSub: '#2ea043', shadow: '0 2px 12px rgba(63,185,80,.25)' };
      case 'failure':   return { bg: 'linear-gradient(135deg,#2a0d0d,#1a0707)', border: '#f85149', textMain: '#f85149', textSub: '#da3633', shadow: '0 2px 12px rgba(248,81,73,.25)' };
      case 'unreached': return { bg: 'linear-gradient(135deg,#161b22,#0d1117)', border: '#21262d', textMain: '#484f58', textSub: '#30363d', shadow: 'none', opacity: 0.5 };
      default:          return { bg: 'linear-gradient(135deg,#1c2128,#161b22)', border: '#30363d', textMain: '#e6edf3', textSub: '#8b949e', shadow: '0 2px 8px rgba(0,0,0,.4)' };
    }
  } else {
    switch (state) {
      case 'active':    return { bg: 'linear-gradient(135deg,#fffbe6,#fff8d6)', border: '#faad14', textMain: '#b45309', textSub: '#92400e', shadow: '0 0 0 0 rgba(250,173,20,.5)', animation: 'nodeGlow 1.8s ease-in-out infinite' };
      case 'success':   return { bg: 'linear-gradient(135deg,#f0fdf4,#dcfce7)', border: '#22c55e', textMain: '#16a34a', textSub: '#15803d', shadow: '0 2px 10px rgba(34,197,94,.2)' };
      case 'failure':   return { bg: 'linear-gradient(135deg,#fff5f5,#fee2e2)', border: '#ef4444', textMain: '#dc2626', textSub: '#b91c1c', shadow: '0 2px 10px rgba(239,68,68,.2)' };
      case 'unreached': return { bg: '#f6f8fa', border: '#d0d7de', textMain: '#9da3ab', textSub: '#c6cdd4', shadow: 'none', opacity: 0.6 };
      default:          return { bg: 'linear-gradient(135deg,#ffffff,#f6f8fa)', border: '#d0d7de', textMain: '#1f2328', textSub: '#636c76', shadow: '0 2px 6px rgba(0,0,0,.08)' };
    }
  }
}

export const CustomStepNode: React.FC<NodeProps> = ({ data }) => {
  const { isDark } = useTheme();
  const d = data as StepNodeData;
  const tok = getStateTokens(d.state, isDark);
  const accent = TYPE_ACCENT[d.stepType] ?? '#888';
  const icon = TYPE_ICON[d.stepType] ?? '●';
  const isActive  = d.state === 'active';
  const isSuccess = d.state === 'success';
  const isFailure = d.state === 'failure';

  const accentColor = isSuccess ? (isDark ? '#3fb950' : '#16a34a')
    : isFailure ? (isDark ? '#f85149' : '#dc2626')
    : isActive  ? '#faad14'
    : accent;

  const handleColor = isDark ? '#30363d' : '#d0d7de';
  const handleBorder = isDark ? '#484f58' : '#9da3ab';

  return (
    <>
      <Handle type="target" position={Position.Top}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      {/* Правый «обходной» хэндл — для рёбер, пропускающих несколько рангов
          (например, fail-переходы в END из ранних шагов), чтобы они шли
          вертикальным коридором справа, а не уходили влево за пределы fitView. */}
      <Handle type="target" id="right" position={Position.Right}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      <Handle type="source" id="right" position={Position.Right}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      {/* Левый хэндл — для второго ребра пары ok/fail, ведущих в один и тот
          же следующий узел (типично для WAIT/EVALUATE), чтобы их подписи
          не накладывались друг на друга на одной вертикальной линии. */}
      <Handle type="target" id="left" position={Position.Left}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      <Handle type="source" id="left" position={Position.Left}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />

      <div style={{
        background: tok.bg,
        border: `${isActive || isSuccess || isFailure ? '2px' : '1.5px'} solid ${tok.border}`,
        borderRadius: 10,
        padding: '10px 14px',
        minWidth: 160,
        maxWidth: 200,
        fontFamily: "'Inter', sans-serif",
        position: 'relative',
        overflow: 'hidden',
        boxShadow: tok.shadow,
        animation: tok.animation,
        opacity: tok.opacity,
        transition: 'box-shadow 0.3s ease, border-color 0.3s ease, opacity 0.3s ease',
      }}>
        {/* Accent bar */}
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, height: 3,
          background: accentColor,
          borderRadius: '10px 10px 0 0',
          transition: 'background 0.3s ease',
        }} />

        {/* Header row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
          <div style={{
            width: 22, height: 22, borderRadius: 6, flexShrink: 0,
            background: `${accentColor}22`,
            border: `1px solid ${accentColor}44`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 12,
          }}>
            {isSuccess ? '✓' : isFailure ? '✗' : isActive ? '▶' : icon}
          </div>
          <span style={{ fontSize: 9, fontWeight: 700, letterSpacing: '0.08em', color: accentColor, textTransform: 'uppercase' }}>
            {TYPE_LABEL[d.stepType]}
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 9, color: tok.textSub, fontWeight: 600 }}>
            #{d.orderIndex}
          </span>
        </div>

        {/* Config label */}
        <div style={{
          fontSize: 11, fontWeight: 600,
          color: isSuccess || isFailure || isActive ? accentColor : tok.textSub,
          lineHeight: 1.4, wordBreak: 'break-word',
        }}>
          {d.configLabel || '—'}
        </div>

        {/* Dot indicator */}
        {(isSuccess || isFailure || isActive) && (
          <div style={{
            position: 'absolute', bottom: 6, right: 8,
            width: 6, height: 6, borderRadius: '50%',
            background: accentColor,
          }} />
        )}
      </div>

      <Handle type="source" position={Position.Bottom}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
    </>
  );
};

export const CustomEndNode: React.FC<NodeProps> = ({ data }) => {
  const { isDark } = useTheme();
  const d = data as { label: string; reached?: boolean };
  const reached = d.reached ?? false;
  const isAbort  = d.label === 'ABORT';

  const bg = reached
    ? isDark
      ? (isAbort ? 'linear-gradient(135deg,#2a0d0d,#1a0707)' : 'linear-gradient(135deg,#0d2a1a,#071a10)')
      : (isAbort ? '#fff5f5' : '#f0fdf4')
    : isDark ? '#161b22' : '#f6f8fa';

  const borderColor = reached
    ? (isAbort ? (isDark ? '#f85149' : '#ef4444') : (isDark ? '#3fb950' : '#22c55e'))
    : (isDark ? '#30363d' : '#d0d7de');

  const textColor = reached
    ? (isAbort ? (isDark ? '#f85149' : '#dc2626') : (isDark ? '#3fb950' : '#16a34a'))
    : (isDark ? '#484f58' : '#9da3ab');

  return (
    <>
      <Handle type="target" position={Position.Top}
        style={{ background: isDark ? '#30363d' : '#d0d7de', border: `1px solid ${isDark ? '#484f58' : '#9da3ab'}`, width: 8, height: 8 }} />
      <Handle type="target" id="right" position={Position.Right}
        style={{ background: isDark ? '#30363d' : '#d0d7de', border: `1px solid ${isDark ? '#484f58' : '#9da3ab'}`, width: 8, height: 8 }} />
      <div style={{
        background: bg,
        border: `${reached ? 2 : 1.5}px solid ${borderColor}`,
        borderRadius: 20,
        padding: '6px 18px',
        color: textColor,
        fontWeight: 700,
        fontSize: 11,
        letterSpacing: '0.08em',
        boxShadow: reached ? `0 2px 10px ${borderColor}44` : 'none',
        transition: 'all 0.3s ease',
        whiteSpace: 'nowrap',
      }}>
        {reached ? (isAbort ? '✗' : '✓') : '●'} {d.label}
      </div>
    </>
  );
};
