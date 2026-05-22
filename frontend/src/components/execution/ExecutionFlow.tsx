import React, { useMemo } from 'react';
import type { NodeTypes } from '@xyflow/react';
import { StepResponse } from '../../types/sequence';
import { StepExecutionResponse } from '../../types/execution';
import { convertStepsToFlowWithHighlight } from '../../utils/flowUtils';
import { CustomStepNode, CustomEndNode } from '../flow/CustomStepNode';
import { FlowWrapper } from '../flow/FlowWrapper';
import { useTheme } from '../../context/ThemeContext';

const STEP_NODE_TYPES: NodeTypes = {
  stepNode: CustomStepNode,
  endNode:  CustomEndNode,
};

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

  return (
    <FlowWrapper
      nodes={nodes}
      edges={edges}
      nodeTypes={STEP_NODE_TYPES}
      height={520}
      readonly
      showMiniMap
      showAutoLayout={false}
    />
  );
};

export const ExecutionFlow = React.memo(ExecutionFlowInner);
