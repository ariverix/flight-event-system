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

export const ExecutionFlow: React.FC<ExecutionFlowProps> = ({ steps, currentStepIndex, stepExecutions }) => {
  const completedStepIndices = useMemo(
    () => stepExecutions.filter((se) => se.result === 'SUCCESS').map((se) => se.stepIndex),
    [stepExecutions]
  );

  const { nodes, edges } = useMemo(
    () => convertStepsToFlowWithHighlight(steps, currentStepIndex, completedStepIndices),
    [steps, currentStepIndex, completedStepIndices]
  );

  return (
    <div style={{ height: '600px', width: '100%' }}>
      <style>
        {`
          @keyframes pulse {
            0%, 100% { box-shadow: 0 0 20px #faad14; }
            50% { box-shadow: 0 0 40px #faad14; }
          }
          .pulse-animation {
            animation: pulse 2s infinite;
          }
        `}
      </style>
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
