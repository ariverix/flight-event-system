import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, MiniMap } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { StepResponse } from '../../types/sequence';
import { StepExecutionResponse } from '../../types/execution';
import { convertStepsToFlowWithHighlight } from '../../utils/flowUtils';

interface ExecutionFlowProps {
  steps: StepResponse[];
  currentStepIndex: number | null;
  stepExecutions: StepExecutionResponse[];
}

const ExecutionFlowInner: React.FC<ExecutionFlowProps> = ({ steps, currentStepIndex, stepExecutions }) => {
  const { nodes, edges } = useMemo(
    () => convertStepsToFlowWithHighlight(steps, currentStepIndex, stepExecutions),
    [steps, currentStepIndex, stepExecutions],
  );

  return (
    <div style={{ height: 600, width: '100%' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={true}
        panOnDrag={true}
        zoomOnScroll={true}
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
};

export const ExecutionFlow = React.memo(ExecutionFlowInner);
