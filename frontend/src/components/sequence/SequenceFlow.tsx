import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, MiniMap } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { StepResponse } from '../../types/sequence';
import { convertStepsToFlow } from '../../utils/flowUtils';

interface SequenceFlowProps {
  steps: StepResponse[];
}

const SequenceFlowInner: React.FC<SequenceFlowProps> = ({ steps }) => {
  const { nodes, edges } = useMemo(() => convertStepsToFlow(steps), [steps]);

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

export const SequenceFlow = React.memo(SequenceFlowInner);
