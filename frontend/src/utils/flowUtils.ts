import dagre from 'dagre';
import { Node, Edge, Position } from '@xyflow/react';
import { StepResponse, StepType, TransitionAction } from '../types/sequence';

const nodeWidth = 200;
const nodeHeight = 80;

const getNodeColor = (stepType: StepType): string => {
  switch (stepType) {
    case 'ACTION':
      return '#1890ff';
    case 'EVALUATE':
      return '#faad14';
    case 'WAIT':
      return '#722ed1';
    default:
      return '#d9d9d9';
  }
};

const getLayoutedElements = (nodes: Node[], edges: Edge[]) => {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));
  dagreGraph.setGraph({ rankdir: 'TB', nodesep: 50, ranksep: 100 });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: nodeWidth, height: nodeHeight });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const layoutedNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    return {
      ...node,
      position: {
        x: nodeWithPosition.x - nodeWidth / 2,
        y: nodeWithPosition.y - nodeHeight / 2,
      },
    };
  });

  return { nodes: layoutedNodes, edges };
};

export const convertStepsToFlow = (steps: StepResponse[]) => {
  const nodes: Node[] = [];
  const edges: Edge[] = [];

  const endNode: Node = {
    id: 'END',
    type: 'default',
    data: { label: 'END' },
    position: { x: 0, y: 0 },
    style: {
      background: '#52c41a',
      color: 'white',
      border: '2px solid #389e0d',
      borderRadius: '8px',
      padding: '10px',
      fontWeight: 'bold',
    },
    sourcePosition: Position.Bottom,
    targetPosition: Position.Top,
  };

  const abortNode: Node = {
    id: 'ABORT',
    type: 'default',
    data: { label: 'ABORT' },
    position: { x: 0, y: 0 },
    style: {
      background: '#ff4d4f',
      color: 'white',
      border: '2px solid #cf1322',
      borderRadius: '8px',
      padding: '10px',
      fontWeight: 'bold',
    },
    sourcePosition: Position.Bottom,
    targetPosition: Position.Top,
  };

  let hasEndNode = false;
  let hasAbortNode = false;

  steps.forEach((step) => {
    const config = JSON.parse(step.configJson);
    const label = `${step.orderIndex}. ${step.stepType}\n${config.actionType || config.criterionType || ''}`;

    nodes.push({
      id: `step-${step.orderIndex}`,
      type: 'default',
      data: { label },
      position: { x: 0, y: 0 },
      style: {
        background: getNodeColor(step.stepType),
        color: 'white',
        border: '2px solid #096dd9',
        borderRadius: '8px',
        padding: '10px',
        fontSize: '12px',
      },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
    });

    const addEdge = (
      action: TransitionAction,
      gotoStep: number | null,
      isSuccess: boolean
    ) => {
      const sourceId = `step-${step.orderIndex}`;
      let targetId: string;
      const edgeColor = isSuccess ? '#52c41a' : '#ff4d4f';
      const edgeStyle = isSuccess ? 'solid' : 'dashed';

      switch (action) {
        case 'CONTINUE':
          const nextStep = steps.find((s) => s.orderIndex === step.orderIndex + 1);
          if (nextStep) {
            targetId = `step-${nextStep.orderIndex}`;
            edges.push({
              id: `${sourceId}-${isSuccess ? 'success' : 'failure'}-${targetId}`,
              source: sourceId,
              target: targetId,
              type: 'smoothstep',
              animated: true,
              style: { stroke: edgeColor, strokeDasharray: edgeStyle === 'dashed' ? '5,5' : '0' },
              label: isSuccess ? 'success' : 'failure',
            });
          }
          break;
        case 'GOTO':
          if (gotoStep !== null) {
            targetId = `step-${gotoStep}`;
            edges.push({
              id: `${sourceId}-${isSuccess ? 'success' : 'failure'}-${targetId}`,
              source: sourceId,
              target: targetId,
              type: 'smoothstep',
              animated: true,
              style: { stroke: edgeColor, strokeDasharray: edgeStyle === 'dashed' ? '5,5' : '0' },
              label: isSuccess ? 'success → goto' : 'failure → goto',
            });
          }
          break;
        case 'END':
          hasEndNode = true;
          edges.push({
            id: `${sourceId}-${isSuccess ? 'success' : 'failure'}-END`,
            source: sourceId,
            target: 'END',
            type: 'smoothstep',
            animated: true,
            style: { stroke: edgeColor, strokeDasharray: edgeStyle === 'dashed' ? '5,5' : '0' },
            label: isSuccess ? 'success → end' : 'failure → end',
          });
          break;
        case 'ABORT':
          hasAbortNode = true;
          edges.push({
            id: `${sourceId}-${isSuccess ? 'success' : 'failure'}-ABORT`,
            source: sourceId,
            target: 'ABORT',
            type: 'smoothstep',
            animated: true,
            style: { stroke: edgeColor, strokeDasharray: edgeStyle === 'dashed' ? '5,5' : '0' },
            label: isSuccess ? 'success → abort' : 'failure → abort',
          });
          break;
      }
    };

    addEdge(step.onSuccessAction, step.onSuccessGotoStep, true);
    addEdge(step.onFailureAction, step.onFailureGotoStep, false);
  });

  if (hasEndNode) nodes.push(endNode);
  if (hasAbortNode) nodes.push(abortNode);

  return getLayoutedElements(nodes, edges);
};

export const convertStepsToFlowWithHighlight = (
  steps: StepResponse[],
  currentStepIndex: number | null,
  completedStepIndices: number[]
) => {
  const { nodes, edges } = convertStepsToFlow(steps);

  const highlightedNodes = nodes.map((node) => {
    if (node.id.startsWith('step-')) {
      const stepIndex = parseInt(node.id.replace('step-', ''));

      if (stepIndex === currentStepIndex) {
        return {
          ...node,
          style: {
            ...node.style,
            border: '4px solid #faad14',
            boxShadow: '0 0 20px #faad14',
          },
          className: 'pulse-animation',
        };
      }

      if (completedStepIndices.includes(stepIndex)) {
        return {
          ...node,
          style: {
            ...node.style,
            border: '3px solid #52c41a',
          },
        };
      }

      return {
        ...node,
        style: {
          ...node.style,
          opacity: 0.5,
          border: '2px solid #d9d9d9',
        },
      };
    }
    return node;
  });

  return { nodes: highlightedNodes, edges };
};
