import dagre from 'dagre';
import { Node, Edge, Position } from '@xyflow/react';
import { StepResponse, TransitionAction } from '../types/sequence';
import { StepExecutionResponse } from '../types/execution';
import { StepNodeData } from '../components/flow/CustomStepNode';

const nodeWidth  = 180;
const nodeHeight = 80;

const CONFIG_LABEL: Record<string, string> = {
  WAIT_TIME:         'Пауза по времени',
  SEND_UPLINK:       'Отправка команды',
  SEND_GROUND:       'Передача на землю',
  RAISE_CONDITION:   'Поднять алерт',
  CLOSE_CONDITION:   'Снять алерт',
  MESSAGE_RECEIVED:  'Получено сообщение',
  FLIGHT_STAGE:      'Фаза полёта',
  POSITION_REPORTED: 'Позиционный отчёт',
  TIME_COMPARISON:   'Сравнение времени',
  CONDITION_ACTIVE:  'Условие активно',
  COMPOUND:          'Составное условие',
};

function getConfigLabel(step: StepResponse): string {
  try {
    const cfg = JSON.parse(step.configJson);
    const key = cfg.actionType || cfg.type || cfg.criterionType;
    const friendly = key ? (CONFIG_LABEL[key] ?? key) : '';
    if (cfg.templateName)    return `${friendly}: ${cfg.templateName}`;
    if (cfg.conditionName)   return `${friendly}: ${cfg.conditionName}`;
    if (cfg.targetStage)     return `${friendly}: ${cfg.targetStage}`;
    if (cfg.durationSeconds) return `${friendly}: ${cfg.durationSeconds}s`;
    return friendly || '—';
  } catch {
    return '—';
  }
}

const layout = (nodes: Node[], edges: Edge[]) => {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 90 });
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

function makeEdge(
  src: string,
  tgt: string,
  label: string,
  isSuccess: boolean,
  isDark: boolean,
  traversed = false,
  dimmed = false,
): Edge {
  const successColor = isDark ? '#3fb950' : '#16a34a';
  const failColor    = isDark ? '#f85149' : '#dc2626';
  const defaultColor = isDark ? '#30363d' : '#d0d7de';
  const labelMuted   = isDark ? '#484f58' : '#9da3ab';

  const strokeColor = traversed ? (isSuccess ? successColor : failColor) : defaultColor;

  return {
    id: `${src}-${isSuccess ? 's' : 'f'}-${tgt}`,
    source: src,
    target: tgt,
    type: 'smoothstep',
    animated: traversed,
    label,
    labelStyle: {
      fill: traversed ? strokeColor : labelMuted,
      fontSize: 10,
      fontWeight: 600,
    },
    labelBgStyle: { fill: 'transparent' },
    style: {
      stroke: strokeColor,
      strokeWidth: traversed ? 2.5 : 1.5,
      strokeDasharray: isSuccess ? '0' : '5,4',
      opacity: dimmed ? 0.18 : traversed ? 1 : 0.6,
      transition: 'stroke 0.4s ease, opacity 0.4s ease',
    },
  };
}

export const convertStepsToFlow = (steps: StepResponse[], isDark = true) => {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  let hasEnd = false, hasAbort = false;

  steps.forEach(step => {
    nodes.push({
      id: `step-${step.orderIndex}`,
      type: 'stepNode',
      data: {
        label: `Step ${step.orderIndex}`,
        stepType: step.stepType,
        configLabel: getConfigLabel(step),
        orderIndex: step.orderIndex,
        state: 'idle',
      } satisfies StepNodeData,
      position: { x: 0, y: 0 },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
    });

    const push = (action: TransitionAction, gotoStep: number | null, isSuccess: boolean) => {
      const src = `step-${step.orderIndex}`;
      let tgt: string | null = null;
      switch (action) {
        case 'CONTINUE': {
          const next = steps.find(s => s.orderIndex === step.orderIndex + 1);
          if (next) tgt = `step-${next.orderIndex}`;
          break;
        }
        case 'GOTO':  if (gotoStep !== null) tgt = `step-${gotoStep}`; break;
        case 'END':   hasEnd = true;   tgt = 'END';   break;
        case 'ABORT': hasAbort = true; tgt = 'ABORT'; break;
      }
      if (!tgt) return;
      edges.push(makeEdge(src, tgt, isSuccess ? 'ok' : 'fail', isSuccess, isDark));
    };

    push(step.onSuccessAction, step.onSuccessGotoStep, true);
    push(step.onFailureAction, step.onFailureGotoStep, false);
  });

  if (hasEnd) nodes.push({
    id: 'END', type: 'endNode',
    data: { label: 'END', reached: false },
    position: { x: 0, y: 0 },
    sourcePosition: Position.Bottom,
    targetPosition: Position.Top,
  });

  if (hasAbort) nodes.push({
    id: 'ABORT', type: 'endNode',
    data: { label: 'ABORT', reached: false },
    position: { x: 0, y: 0 },
    sourcePosition: Position.Bottom,
    targetPosition: Position.Top,
  });

  return layout(nodes, edges);
};

export const convertStepsToFlowWithHighlight = (
  steps: StepResponse[],
  currentStepIndex: number | null,
  stepExecutions: StepExecutionResponse[],
  isDark = true,
) => {
  const { nodes, edges } = convertStepsToFlow(steps, isDark);

  const completedMap = new Map<number, { result: string; action: string | null; target: number | null }>();
  for (const se of stepExecutions) {
    if (se.result) {
      completedMap.set(se.stepIndex, {
        result: se.result,
        action: se.transitionAction ?? null,
        target: se.transitionTarget ?? null,
      });
    }
  }

  // Build traversed edge IDs and reached terminals
  const traversedEdges   = new Set<string>();
  const reachedTerminals = new Set<string>();

  for (const [stepIdx, info] of completedMap) {
    const src    = `step-${stepIdx}`;
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
      case 'GOTO':  if (info.target !== null) tgt = `step-${info.target}`; break;
      case 'END':   tgt = 'END';   reachedTerminals.add('END');   break;
      case 'ABORT': tgt = 'ABORT'; reachedTerminals.add('ABORT'); break;
    }
    if (tgt) traversedEdges.add(`${src}-${suffix}-${tgt}`);
  }

  // Highlight nodes
  const highlightedNodes = nodes.map(node => {
    if (node.type === 'endNode') {
      return { ...node, data: { ...node.data, reached: reachedTerminals.has(node.id as string) } };
    }
    if (!node.id.startsWith('step-')) return node;

    const stepIdx = parseInt(node.id.replace('step-', ''), 10);
    let state: StepNodeData['state'] = 'unreached';

    if (stepIdx === currentStepIndex && !completedMap.has(stepIdx)) {
      state = 'active';
    } else if (completedMap.has(stepIdx)) {
      state = completedMap.get(stepIdx)!.result === 'SUCCESS' ? 'success' : 'failure';
    }

    return { ...node, data: { ...(node.data as StepNodeData), state } };
  });

  // Highlight edges
  const anyTraversed = traversedEdges.size > 0;
  const highlightedEdges = edges.map(edge => {
    const isTraversed = traversedEdges.has(edge.id);
    const isSuccess   = edge.id.includes('-s-');
    return makeEdge(
      edge.source, edge.target,
      (edge.label as string) ?? '',
      isSuccess, isDark,
      isTraversed,
      anyTraversed && !isTraversed,
    );
  });

  return { nodes: highlightedNodes, edges: highlightedEdges };
};
