import React, { useMemo } from 'react';
import type { NodeTypes } from '@xyflow/react';
import { StepResponse } from '../../types/sequence';
import { convertStepsToFlow } from '../../utils/flowUtils';
import { CustomStepNode, CustomEndNode } from '../flow/CustomStepNode';
import { FlowWrapper } from '../flow/FlowWrapper';
import { useTheme } from '../../context/ThemeContext';

const STEP_NODE_TYPES: NodeTypes = {
  stepNode: CustomStepNode,
  endNode:  CustomEndNode,
};

interface SequenceFlowProps { steps: StepResponse[]; }

const SequenceFlowInner: React.FC<SequenceFlowProps> = ({ steps }) => {
  const { isDark } = useTheme();
  const { nodes, edges } = useMemo(() => convertStepsToFlow(steps, isDark), [steps, isDark]);

  return (
    <FlowWrapper
      nodes={nodes}
      edges={edges}
      nodeTypes={STEP_NODE_TYPES}
      height={520}
      readonly
      showMiniMap
      showAutoLayout
    />
  );
};

export const SequenceFlow = React.memo(SequenceFlowInner);
