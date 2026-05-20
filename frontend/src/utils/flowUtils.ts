import dagre from 'dagre';
import { Node, Edge, Position } from '@xyflow/react';
import { StepResponse, StepType, TransitionAction } from '../types/sequence';
import { StepExecutionResponse } from '../types/execution';

const nodeWidth = 200;
const nodeHeight = 80;

const TYPE_COLOR: Record<StepType, string> = {
  ACTION:   '#1677ff',
  EVALUATE: '#faad14',
  WAIT:     '#722ed1',
};

const getLayoutedElements = (nodes: Node[], edges: Edge[]) => {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 50, ranksep: 100 });

  nodes.forEach(n => g.setNode(n.id, { width: nodeWidth, height: nodeHeight }));
  edges.forEach(e => g.setEdge(e.source, e.target));

  dagre.layout(g);

  return {
    nodes: nodes.map(n => {
      const pos = g.node(n.id);
      return { ...n, position: { x: pos.x - nodeWidth / 2, y: pos.y - nodeHeight / 2 } };
    }),
    edges,
  };
};

export const convertStepsToFlow = (steps: StepResponse[]) => {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  let hasEnd = false;
  let hasAbort = false;

  const endNode: Node = {
    id: 'END', type: 'default',
    data: { label: 'END' },
    position: { x: 0, y: 0 },
    style: { background: '#52c41a', color: 'white', border: '2px solid #389e0d', borderRadius: 8, padding: 10, fontWeight: 'bold' },
    sourcePosition: Position.Bottom, targetPosition: Position.Top,
  };

  const abortNode: Node = {
    id: 'ABORT', type: 'default',
    data: { label: 'ABORT' },
    position: { x: 0, y: 0 },
    style: { background: '#ff4d4f', color: 'white', border: '2px solid #cf1322', borderRadius: 8, padding: 10, fontWeight: 'bold' },
    sourcePosition: Position.Bottom, targetPosition: Position.Top,
  };

  steps.forEach(step => {
    let config: any = {};
    try { config = JSON.parse(step.configJson); } catch { /* keep empty */ }

    const typeLabel = config.actionType || config.type || config.criterionType || '';
    const label = `${step.orderIndex}. ${step.stepType}${typeLabel ? `\n${typeLabel}` : ''}`;
    const color = TYPE_COLOR[step.stepType] ?? '#d9d9d9';

    nodes.push({
      id: `step-${step.orderIndex}`,
      type: 'default',
      data: { label },
      position: { x: 0, y: 0 },
      style: {
        background: color,
        color: 'white',
        border: `2px solid ${color}`,
        borderRadius: 8,
        padding: 10,
        fontSize: 12,
        whiteSpace: 'pre-line',
      },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
    });

    const pushEdge = (action: TransitionAction, gotoStep: number | null, isSuccess: boolean) => {
      const src = `step-${step.orderIndex}`;
      const edgeColor = isSuccess ? '#52c41a' : '#ff4d4f';
      const label = isSuccess ? 'success' : 'failure';

      let tgt: string | null = null;
      switch (action) {
        case 'CONTINUE': {
          const next = steps.find(s => s.orderIndex === step.orderIndex + 1);
          if (next) tgt = `step-${next.orderIndex}`;
          break;
        }
        case 'GOTO':
          if (gotoStep !== null) tgt = `step-${gotoStep}`;
          break;
        case 'END':
          hasEnd = true;
          tgt = 'END';
          break;
        case 'ABORT':
          hasAbort = true;
          tgt = 'ABORT';
          break;
      }

      if (!tgt) return;

      edges.push({
        id: `${src}-${isSuccess ? 's' : 'f'}-${tgt}`,
        source: src,
        target: tgt,
        type: 'smoothstep',
        animated: true,
        style: { stroke: edgeColor, strokeDasharray: isSuccess ? '0' : '5,5', strokeWidth: 2 },
        label,
        labelStyle: { fill: edgeColor, fontSize: 11, fontWeight: 600 },
        labelBgStyle: { fill: 'transparent' },
      });
    };

    pushEdge(step.onSuccessAction, step.onSuccessGotoStep, true);
    pushEdge(step.onFailureAction, step.onFailureGotoStep, false);
  });

  if (hasEnd)   nodes.push(endNode);
  if (hasAbort) nodes.push(abortNode);

  return getLayoutedElements(nodes, edges);
};

export const convertStepsToFlowWithHighlight = (
  steps: StepResponse[],
  currentStepIndex: number | null,
  stepExecutions: StepExecutionResponse[],
) => {
  const { nodes, edges } = convertStepsToFlow(steps);

  // Build a set of completed step indices and their transition actions
  const completedMap = new Map<number, { result: string; action: string | null; target: number | null }>();
  for (const se of stepExecutions) {
    if (se.result === 'SUCCESS' || se.result === 'FAILURE') {
      completedMap.set(se.stepIndex, {
        result: se.result,
        action: se.transitionAction ?? null,
        target: se.transitionTarget ?? null,
      });
    }
  }

  // Highlight nodes
  const highlightedNodes = nodes.map(node => {
    if (!node.id.startsWith('step-')) return node;

    const stepIdx = parseInt(node.id.replace('step-', ''), 10);

    // Show yellow pulse only for the actively executing step (not yet completed)
    if (stepIdx === currentStepIndex && !completedMap.has(stepIdx)) {
      return {
        ...node,
        className: 'rf-pulse',
        style: {
          ...node.style,
          background: '#faad14',
          border: '3px solid #d48806',
          color: 'white',
          opacity: 1,
        },
      };
    }

    if (completedMap.has(stepIdx)) {
      const info = completedMap.get(stepIdx)!;
      const isSuccess = info.result === 'SUCCESS';
      return {
        ...node,
        style: {
          ...node.style,
          background: isSuccess ? '#52c41a' : '#ff4d4f',
          border: `3px solid ${isSuccess ? '#389e0d' : '#cf1322'}`,
          color: 'white',
          opacity: 1,
        },
      };
    }

    // Unreached step
    return {
      ...node,
      style: {
        ...node.style,
        background: '#484f58',
        border: '2px solid #30363d',
        color: '#9da3ab',
        opacity: 0.55,
      },
    };
  });

  // Highlight edges: mark edges that were actually traversed
  const traversedEdges = new Set<string>();
  for (const [stepIdx, info] of completedMap) {
    const src = `step-${stepIdx}`;
    const suffix = info.result === 'SUCCESS' ? 's' : 'f';
    let tgt: string | null = null;

    switch (info.action) {
      case 'CONTINUE': {
        const step = steps.find(s => s.orderIndex === stepIdx);
        if (step) {
          const next = steps.find(s => s.orderIndex === step.orderIndex + 1);
          if (next) tgt = `step-${next.orderIndex}`;
        }
        break;
      }
      case 'GOTO':
        if (info.target !== null) tgt = `step-${info.target}`;
        break;
      case 'END':
        tgt = 'END';
        break;
      case 'ABORT':
        tgt = 'ABORT';
        break;
    }

    if (tgt) traversedEdges.add(`${src}-${suffix}-${tgt}`);
  }

  const highlightedEdges = edges.map(edge => {
    if (traversedEdges.has(edge.id)) {
      const isSuccess = edge.id.includes('-s-');
      return {
        ...edge,
        animated: true,
        style: {
          ...edge.style,
          stroke: isSuccess ? '#52c41a' : '#ff4d4f',
          strokeWidth: 3,
          opacity: 1,
        },
      };
    }
    // Dim non-traversed edges
    return {
      ...edge,
      animated: false,
      style: {
        ...edge.style,
        opacity: 0.25,
        strokeWidth: 1,
      },
    };
  });

  return { nodes: highlightedNodes, edges: highlightedEdges };
};
