import React from 'react';
import { Handle, Position, NodeProps } from '@xyflow/react';
import { useTheme } from '../../context/ThemeContext';
import { getTypeAccent, getStateTokens, getHandleColors } from '../../utils/stepTypeColors';

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

const CustomStepNodeImpl: React.FC<NodeProps> = ({ data }) => {
  const { isDark } = useTheme();
  const d = data as StepNodeData;
  const tok = getStateTokens(d.state, isDark);
  const accent = getTypeAccent(d.stepType, isDark);
  const icon = TYPE_ICON[d.stepType] ?? '●';
  const isActive  = d.state === 'active';
  const isSuccess = d.state === 'success';
  const isFailure = d.state === 'failure';

  const accentColor = isSuccess || isFailure || isActive ? tok.border : accent;

  const { background: handleColor, border: handleBorder } = getHandleColors(isDark);

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
        border: `${isActive || isSuccess || isFailure ? '1.5px' : '1px'} solid ${tok.border}`,
        borderRadius: 10,
        padding: '10px 14px',
        minWidth: 160,
        maxWidth: 200,
        fontFamily: "-apple-system,'SF Pro Display','Inter',system-ui,sans-serif",
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

/** Мемоизирован по аналогии с ExecutionFlow/SequenceFlow — граф может содержать десятки нод. */
export const CustomStepNode = React.memo(CustomStepNodeImpl);

const CustomEndNodeImpl: React.FC<NodeProps> = ({ data }) => {
  const { isDark } = useTheme();
  const d = data as { label: string; reached?: boolean };
  const reached = d.reached ?? false;
  const isAbort  = d.label === 'ABORT';

  const bg = reached
    ? isDark
      ? (isAbort ? 'rgba(255,69,58,0.12)' : 'rgba(48,209,88,0.12)')
      : (isAbort ? 'rgba(255,59,48,0.10)' : 'rgba(52,199,89,0.10)')
    : isDark ? '#262626' : '#f5f5f7';

  const borderColor = reached
    ? (isAbort ? (isDark ? '#ff453a' : '#ff3b30') : (isDark ? '#30d158' : '#34c759'))
    : (isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)');

  const textColor = reached
    ? (isAbort ? (isDark ? '#ff453a' : '#b91c1c') : (isDark ? '#30d158' : '#15803d'))
    : (isDark ? 'rgba(255,255,255,0.35)' : '#8e8e93');

  const { background: handleColor, border: handleBorder } = getHandleColors(isDark);

  return (
    <>
      <Handle type="target" position={Position.Top}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      <Handle type="target" id="right" position={Position.Right}
        style={{ background: handleColor, border: `1px solid ${handleBorder}`, width: 8, height: 8 }} />
      <div style={{
        background: bg,
        border: `1px solid ${borderColor}`,
        borderRadius: 20,
        padding: '6px 18px',
        color: textColor,
        fontWeight: 600,
        fontSize: 11,
        letterSpacing: '0.04em',
        boxShadow: 'none',
        transition: 'background 0.3s ease, border-color 0.3s ease, color 0.3s ease',
        whiteSpace: 'nowrap',
      }}>
        {reached ? (isAbort ? '✗' : '✓') : '●'} {d.label}
      </div>
    </>
  );
};

export const CustomEndNode = React.memo(CustomEndNodeImpl);
