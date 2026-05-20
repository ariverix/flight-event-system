import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, MiniMap, BackgroundVariant } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { StepResponse } from '../../types/sequence';
import { StepExecutionResponse } from '../../types/execution';
import { convertStepsToFlowWithHighlight } from '../../utils/flowUtils';
import { CustomStepNode, CustomEndNode, StepNodeData } from '../flow/CustomStepNode';

const nodeTypes = {
  stepNode: CustomStepNode,
  endNode: CustomEndNode,
};

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
    <div style={{ height: 560, width: '100%', borderRadius: 8, overflow: 'hidden' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.25 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnDrag={true}
        zoomOnScroll={true}
        minZoom={0.3}
        maxZoom={2}
      >
        <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#21262d" />
        <Controls />
        <MiniMap
          nodeColor={(n) => {
            const d = n.data as StepNodeData;
            if (d?.state === 'success') return '#3fb950';
            if (d?.state === 'failure') return '#f85149';
            if (d?.state === 'active') return '#faad14';
            if (d?.state === 'unreached') return '#21262d';
            return '#30363d';
          }}
          maskColor="rgba(13,17,23,0.7)"
          style={{ background: '#161b22', border: '1px solid #30363d' }}
        />
      </ReactFlow>
    </div>
  );
};

export const ExecutionFlow = React.memo(ExecutionFlowInner);
