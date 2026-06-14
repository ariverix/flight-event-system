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

// Краткое пояснение исхода ребра — что означает переход ok/fail для
// данного типа шага (показывается рядом с меткой ok/fail на графе).
const OUTCOME_HINT: Record<string, { ok: string; fail: string }> = {
  ACTION:   { ok: 'успешно',  fail: 'ошибка' },
  EVALUATE: { ok: 'условие верно', fail: 'условие неверно' },
  WAIT:     { ok: 'получено', fail: 'таймаут' },
};

function getOutcomeHint(stepType: string, isSuccess: boolean): string {
  const hint = OUTCOME_HINT[stepType];
  if (!hint) return '';
  return isSuccess ? hint.ok : hint.fail;
}

const layout = (nodes: Node[], edges: Edge[]) => {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  // ranksep=60 → вертикальный шаг между узлами = nodeHeight(80) + 60 = 140px.
  // Прежнее значение 150 давало шаг 230px — при 7 шагах граф растягивался
  // до ~1600px и fitView приходилось сжимать до scale≈0.56 (подписи узлов
  // становились нечитаемыми).
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 60 });
  // Терминальные узлы (END/ABORT) рендерятся в DOM как маленькая «пилюля»
  // (~90×34), а не как полноразмерная карточка шага. Раньше они заводились в
  // dagre с размером 180×80, из-за чего их центр (и, значит, верхний target-
  // хэндл) смещался вниз — рёбра приходили в END с заметным зазором. Заводим
  // их с реальным размером, чтобы рёбра стыковались точно.
  nodes.forEach(n => g.setNode(
    n.id,
    n.type === 'endNode'
      ? { width: 90, height: 34 }
      : { width: nodeWidth, height: nodeHeight },
  ));
  edges.forEach(e => {
    // Рёбра правого коридора (skip, см. makeEdge) не должны влиять на
    // раскладку — иначе dagre вставляет на каждом промежуточном ранге
    // «фиктивные» узлы для длинных рёбер, что сдвигает рёбра вправо
    // («лесенка») и ломает позиционирование подписей рёбер.
    if (e.sourceHandle === 'right') return;
    g.setEdge(e.source, e.target);
  });
  dagre.layout(g);
  // Все узлы выравниваем по одной вертикальной оси: x фиксирован для всех
  // узлов (вне зависимости от того, что вернул dagre для конкретного ранга)
  // — иначе балансировка дерева даёт каждому рангу чуть свой x и цепочка
  // визуально превращается в «лесенку». Берём только y (ранг) из dagre.
  return {
    nodes: nodes.map(n => {
      const pos = g.node(n.id);
      // Смещаем по фактической высоте узла из dagre (терминалы ниже карточек) —
      // иначе верхний край END уезжал бы относительно его центра.
      const h = (pos as { height?: number }).height ?? nodeHeight;
      return { ...n, position: { x: 0, y: pos.y - h / 2 } };
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
  routeHandle?: 'right' | 'left',
): Edge {
  const successColor = isDark ? '#3fb950' : '#16a34a';
  const failColor    = isDark ? '#f85149' : '#dc2626';
  const defaultColor = isDark ? '#30363d' : '#d0d7de';

  const fullColor = isSuccess ? successColor : failColor;
  const dimColor  = isSuccess
    ? (isDark ? 'rgba(63,185,80,0.60)'  : 'rgba(22,163,74,0.60)')
    : (isDark ? 'rgba(248,81,73,0.60)'  : 'rgba(220,38,38,0.60)');

  const strokeColor = traversed ? fullColor : defaultColor;

  return {
    id: `${src}-${isSuccess ? 's' : 'f'}-${tgt}`,
    source: src,
    target: tgt,
    ...(routeHandle ? { sourceHandle: routeHandle, targetHandle: routeHandle } : {}),
    // Узкий правый коридор для skip-рёбер: держит вертикальный участок близко
    // к узлам, чтобы коридор не «уводил» взгляд вбок (эффект лесенки) и не
    // вылезал за правый край канваса. Подписи таких рёбер всё равно ставятся
    // слева от источника (см. attachLabelPositions), коридор влияет только
    // на саму линию ребра.
    ...(routeHandle === 'right' ? { pathOptions: { offset: 20, borderRadius: 6 } } : {}),
    type: 'labeled',
    animated: traversed,
    label,
    // labelAbsX/labelAbsY проставляются позже, в attachLabelPositions —
    // после layout(), когда известны финальные координаты узлов.
    data: {},
    labelStyle: {
      fill: traversed ? fullColor : dimColor,
      fontSize: 10,
      fontWeight: 700,
    },
    labelBgStyle: {
      fill: isDark ? 'rgba(6,7,18,0.92)' : 'rgba(255,255,255,0.96)',
      stroke: isSuccess ? 'rgba(16,185,129,0.30)' : 'rgba(239,68,68,0.30)',
      strokeWidth: 0.8,
      rx: 5, ry: 5,
    },
    labelBgPadding: [7, 4] as [number, number],
    zIndex: 10,
    style: {
      stroke: strokeColor,
      strokeWidth: traversed ? 2.5 : 1.5,
      strokeDasharray: isSuccess ? '0' : '5,4',
      opacity: dimmed ? 0.18 : traversed ? 1 : 0.6,
      transition: 'stroke 0.4s ease, opacity 0.4s ease',
    },
  };
}

// Подписи рёбер ставим по бокам узла-источника — ok справа, fail слева
// (x < 0 и x > nodeWidth — там нет ни узлов, ни линий рёбер: все узлы лежат
// в x:[0, nodeWidth], вертикальные рёбра идут через их центр x=nodeWidth/2,
// правый коридор для skip-рёбер — ещё дальше справа). У каждого шага свой
// ранг по Y (шаг между рангами 140px), поэтому подписи разных шагов
// автоматически попадают в разные строки и никогда не накладываются друг на
// друга — независимо от того, ведёт ребро к следующему шагу, через GOTO или
// в правый коридор к END. На каждой стороне у шага не больше одной подписи
// (один ok и один fail), поэтому по вертикали подпись всегда центрируем.
const LABEL_GAP_LEFT  = 76;
// Подписи ok-рёбер (справа) центрируются по своему тексту, и при длинных
// подписях ("ok · условие верно") левый край фона подходил почти к правому
// краю узла. Увеличенный отступ отодвигает их дальше на линию ребра.
const LABEL_GAP_RIGHT = 104;
function attachLabelPositions(nodes: Node[], edges: Edge[]) {
  const posOf = (id: string) => nodes.find(n => n.id === id)?.position ?? { x: 0, y: 0 };

  edges.forEach(edge => {
    const pos = posOf(edge.source);
    const isSuccess = edge.id.includes('-s-');
    edge.data = {
      ...(edge.data ?? {}),
      labelAbsX: isSuccess ? pos.x + nodeWidth + LABEL_GAP_RIGHT : pos.x - LABEL_GAP_LEFT,
      labelAbsY: pos.y + nodeHeight * 0.5,
    };
  });
}

export const convertStepsToFlow = (steps: StepResponse[], isDark = true) => {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  let hasEnd = false, hasAbort = false;
  const maxOrderIndex = Math.max(...steps.map(s => s.orderIndex));

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

    // "Прыжок" через несколько рангов (например fail -> END из непоследнего
    // шага, или GOTO назад/вперёд через шаг) — такие рёбра ведём через
    // правый коридор, иначе dagre/smoothstep уводят их далеко влево
    // за пределы fitView.
    const resolveTarget = (action: TransitionAction, gotoStep: number | null): { tgt: string | null; skip: boolean } => {
      switch (action) {
        case 'CONTINUE': {
          const next = steps.find(s => s.orderIndex === step.orderIndex + 1);
          return { tgt: next ? `step-${next.orderIndex}` : null, skip: false };
        }
        case 'GOTO':
          if (gotoStep !== null) {
            return { tgt: `step-${gotoStep}`, skip: gotoStep !== step.orderIndex + 1 };
          }
          return { tgt: null, skip: false };
        case 'END':
          hasEnd = true;
          return { tgt: 'END', skip: step.orderIndex !== maxOrderIndex };
        case 'ABORT':
          hasAbort = true;
          return { tgt: 'ABORT', skip: step.orderIndex !== maxOrderIndex };
        default:
          return { tgt: null, skip: false };
      }
    };

    const src = `step-${step.orderIndex}`;
    const success = resolveTarget(step.onSuccessAction, step.onSuccessGotoStep);
    const failure = resolveTarget(step.onFailureAction, step.onFailureGotoStep);

    // "Прыжковые" рёбра (skip) ведём через правый коридор, чтобы линия не
    // уходила далеко влево за пределы fitView. Подписи всех рёбер (включая
    // эти) ставятся отдельно в attachLabelPositions — слева от узла-источника.
    const successHandle = success.skip ? 'right' : undefined;
    const failureHandle = failure.skip ? 'right' : undefined;

    if (success.tgt) {
      const hint = getOutcomeHint(step.stepType, true);
      const label = hint ? `ok · ${hint}` : 'ok';
      edges.push(makeEdge(src, success.tgt, label, true, isDark, false, false, successHandle));
    }
    if (failure.tgt) {
      const hint = getOutcomeHint(step.stepType, false);
      const label = hint ? `fail · ${hint}` : 'fail';
      edges.push(makeEdge(src, failure.tgt, label, false, isDark, false, false, failureHandle));
    }
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

  const laidOut = layout(nodes, edges);
  attachLabelPositions(laidOut.nodes, laidOut.edges);
  return laidOut;
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
    const routeHandle = edge.sourceHandle as 'right' | 'left' | undefined;
    return makeEdge(
      edge.source, edge.target,
      (edge.label as string) ?? '',
      isSuccess, isDark,
      isTraversed,
      anyTraversed && !isTraversed,
      routeHandle,
    );
  });
  attachLabelPositions(highlightedNodes, highlightedEdges);

  return { nodes: highlightedNodes, edges: highlightedEdges };
};
