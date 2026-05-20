import React, { useMemo, useState } from 'react';
import { ReactFlow, Background, Controls, MiniMap, BackgroundVariant } from '@xyflow/react';
import { Button, Tooltip } from 'antd';
import { FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import '@xyflow/react/dist/style.css';
import { StepResponse } from '../../types/sequence';
import { StepExecutionResponse } from '../../types/execution';
import { convertStepsToFlowWithHighlight } from '../../utils/flowUtils';
import { CustomStepNode, CustomEndNode, StepNodeData } from '../flow/CustomStepNode';
import { useTheme } from '../../context/ThemeContext';

const nodeTypes = { stepNode: CustomStepNode, endNode: CustomEndNode };

interface ExecutionFlowProps {
  steps: StepResponse[];
  currentStepIndex: number | null;
  stepExecutions: StepExecutionResponse[];
}

const ExecutionFlowInner: React.FC<ExecutionFlowProps> = ({ steps, currentStepIndex, stepExecutions }) => {
  const { isDark } = useTheme();
  const [fullscreen, setFullscreen] = useState(false);
  const { nodes, edges } = useMemo(
    () => convertStepsToFlowWithHighlight(steps, currentStepIndex, stepExecutions, isDark),
    [steps, currentStepIndex, stepExecutions, isDark],
  );

  const bgColor  = isDark ? '#070d1a' : '#f0f4ff';
  const dotColor = isDark ? '#1e2a3a' : '#c8d5f0';
  const miniMask = isDark ? 'rgba(7,13,26,0.75)' : 'rgba(240,244,255,0.75)';
  const miniStyle = isDark
    ? { background: '#0d1530', border: '1px solid rgba(255,255,255,0.1)' }
    : { background: '#fff',    border: '1px solid rgba(0,0,0,0.1)' };
  const ctrlStyle = isDark
    ? { background: 'rgba(13,21,48,0.9)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 8 }
    : { background: 'rgba(255,255,255,0.9)', border: '1px solid rgba(0,0,0,0.1)', borderRadius: 8 };

  const containerStyle: React.CSSProperties = fullscreen
    ? { position: 'fixed', inset: 0, zIndex: 1000, borderRadius: 0 }
    : { height: 520, borderRadius: 10, overflow: 'hidden' };

  return (
    <div style={{ ...containerStyle, width: '100%', background: bgColor, position: fullscreen ? 'fixed' : 'relative' }}>
      <Tooltip title={fullscreen ? 'Свернуть' : 'На весь экран'}>
        <Button
          size="small"
          type="text"
          icon={fullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
          onClick={() => setFullscreen(f => !f)}
          style={{
            position: 'absolute', top: 10, right: 10, zIndex: 10,
            background: isDark ? 'rgba(13,21,48,0.8)' : 'rgba(255,255,255,0.8)',
            border: `1px solid ${isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)'}`,
            color: isDark ? 'rgba(255,255,255,0.7)' : '#636c76',
          }}
        />
      </Tooltip>

      <ReactFlow
        nodes={nodes} edges={edges}
        nodeTypes={nodeTypes}
        fitView fitViewOptions={{ padding: 0.25 }}
        nodesDraggable={false} nodesConnectable={false}
        elementsSelectable={false}
        panOnDrag zoomOnScroll
        minZoom={0.2} maxZoom={3}
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} gap={22} size={1} color={dotColor} />
        <Controls style={ctrlStyle} />
        <MiniMap nodeColor={n => {
          const d = n.data as StepNodeData;
          if (d?.state === 'success')   return isDark ? '#3fb950' : '#22c55e';
          if (d?.state === 'failure')   return isDark ? '#f85149' : '#ef4444';
          if (d?.state === 'active')    return '#f59e0b';
          if (d?.state === 'unreached') return isDark ? '#1e2a3a' : '#e0e7ff';
          return isDark ? '#1e2a3a' : '#c8d5f0';
        }} maskColor={miniMask} style={miniStyle} />
      </ReactFlow>
    </div>
  );
};

export const ExecutionFlow = React.memo(ExecutionFlowInner);
