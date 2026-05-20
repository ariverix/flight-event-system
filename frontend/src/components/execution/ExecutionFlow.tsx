import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, MiniMap, BackgroundVariant } from '@xyflow/react';
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
  const { nodes, edges } = useMemo(
    () => convertStepsToFlowWithHighlight(steps, currentStepIndex, stepExecutions, isDark),
    [steps, currentStepIndex, stepExecutions, isDark],
  );

  const bgColor   = isDark ? '#0d1117' : '#f6f8fa';
  const dotColor  = isDark ? '#21262d' : '#d0d7de';
  const miniMask  = isDark ? 'rgba(13,17,23,0.75)' : 'rgba(246,248,250,0.75)';
  const miniStyle = isDark
    ? { background: '#161b22', border: '1px solid #30363d' }
    : { background: '#ffffff', border: '1px solid #d0d7de' };

  return (
    <div style={{ height: 520, width: '100%', borderRadius: 8, overflow: 'hidden', background: bgColor }}>
      <ReactFlow
        nodes={nodes} edges={edges}
        nodeTypes={nodeTypes}
        fitView fitViewOptions={{ padding: 0.25 }}
        nodesDraggable={false} nodesConnectable={false}
        elementsSelectable={false}
        panOnDrag zoomOnScroll
        minZoom={0.25} maxZoom={2.5}
      >
        <Background variant={BackgroundVariant.Dots} gap={22} size={1} color={dotColor} />
        <Controls style={{ background: isDark ? '#161b22' : '#fff', border: `1px solid ${isDark ? '#30363d' : '#d0d7de'}` }} />
        <MiniMap
          nodeColor={n => {
            const d = n.data as StepNodeData;
            if (d?.state === 'success') return isDark ? '#3fb950' : '#22c55e';
            if (d?.state === 'failure') return isDark ? '#f85149' : '#ef4444';
            if (d?.state === 'active')  return '#faad14';
            if (d?.state === 'unreached') return isDark ? '#21262d' : '#e8ecf0';
            return isDark ? '#30363d' : '#d0d7de';
          }}
          maskColor={miniMask}
          style={miniStyle}
        />
      </ReactFlow>
    </div>
  );
};

export const ExecutionFlow = React.memo(ExecutionFlowInner);
