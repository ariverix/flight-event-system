import React, { useMemo } from 'react';
import { ReactFlow, Background, Controls, MiniMap, BackgroundVariant } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { StepResponse } from '../../types/sequence';
import { convertStepsToFlow } from '../../utils/flowUtils';
import { CustomStepNode, CustomEndNode } from '../flow/CustomStepNode';

const nodeTypes = {
  stepNode: CustomStepNode,
  endNode: CustomEndNode,
};

interface SequenceFlowProps {
  steps: StepResponse[];
}

const SequenceFlowInner: React.FC<SequenceFlowProps> = ({ steps }) => {
  const { nodes, edges } = useMemo(() => convertStepsToFlow(steps), [steps]);

  return (
    <div style={{ height: 560, width: '100%', borderRadius: 8, overflow: 'hidden' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
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
            const d = n.data as any;
            if (d?.state === 'success') return '#3fb950';
            if (d?.state === 'failure') return '#f85149';
            if (d?.state === 'active') return '#faad14';
            if (d?.stepType === 'ACTION') return '#1677ff';
            if (d?.stepType === 'EVALUATE') return '#faad14';
            if (d?.stepType === 'WAIT') return '#7c3aed';
            return '#30363d';
          }}
          maskColor="rgba(13,17,23,0.7)"
          style={{ background: '#161b22', border: '1px solid #30363d' }}
        />
      </ReactFlow>
    </div>
  );
};

export const SequenceFlow = React.memo(SequenceFlowInner);
