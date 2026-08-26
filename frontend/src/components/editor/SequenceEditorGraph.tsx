/**
 * Интерактивный React Flow-граф редактора последовательности (P7-2).
 *
 * Особенности редакторного режима (vs SequenceFlow read-only):
 *  - управляемые nodes/edges (useNodesState / useEdgesState)
 *  - пересчёт графа при изменении steps (от store → props)
 *  - клик по узлу — выбор шага, передаётся вверх через onNodeSelect
 *  - клик по канвасу — сброс выбора
 *  - узлы перетаскиваемы для ручного позиционирования в канвасе
 *    (canvas-drag не влияет на порядок шагов; порядок меняется только
 *     через EditorStepList)
 *  - новые соединения не создаются вручную (nodesConnectable=false)
 *  - fitView запускается только при изменении steps/темы, а не при выборе
 *
 * Переиспользует:
 *  - convertStepsToFlow из flowUtils.ts (layout + рёбра-решения)
 *  - CustomStepNode / CustomEndNode — кастомные ноды с Liquid Glass
 *  - LabeledEdge — рёбра с подписями (ok/fail)
 */

import React, {
  useEffect,
  useMemo,
  useRef,
  useCallback,
} from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  Panel,
  useNodesState,
  useEdgesState,
  useReactFlow,
} from '@xyflow/react';
import type { Node, Edge, NodeTypes, EdgeTypes } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AimOutlined, ReloadOutlined, FullscreenOutlined } from '@ant-design/icons';

import { convertStepsToFlow } from '../../utils/flowUtils';
import { getAutoLayout } from '../../utils/graphLayout';
import { CustomStepNode, CustomEndNode } from '../flow/CustomStepNode';
import { LabeledEdge } from '../flow/LabeledEdge';
import { getTypeAccent } from '../../utils/stepTypeColors';
import { useTheme } from '../../context/ThemeContext';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import type { StepResponse } from '../../types/sequence';

// ── Типы нод/рёбер ────────────────────────────────────────────────────────────

const nodeTypes: NodeTypes = {
  stepNode: CustomStepNode,
  endNode:  CustomEndNode,
};

const edgeTypes: EdgeTypes = {
  labeled: LabeledEdge,
};

// ── Кнопка панели инструментов ────────────────────────────────────────────────

interface PanelBtnProps {
  icon: React.ReactNode;
  title: string;
  onClick: () => void;
  isDark: boolean;
}

const PanelBtn: React.FC<PanelBtnProps> = ({ icon, title, onClick, isDark }) => (
  <button
    title={title}
    onClick={onClick}
    style={{
      background: 'transparent',
      border: 'none',
      color: isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)',
      width: 30,
      height: 30,
      borderRadius: 8,
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: 14,
      transition: 'all 0.15s ease',
      padding: 0,
    }}
    onMouseEnter={e => {
      const el = e.currentTarget as HTMLButtonElement;
      el.style.background = isDark ? 'rgba(255,255,255,0.09)' : 'rgba(0,0,0,0.07)';
      el.style.color = isDark ? 'rgba(255,255,255,0.90)' : 'rgba(0,0,0,0.80)';
    }}
    onMouseLeave={e => {
      const el = e.currentTarget as HTMLButtonElement;
      el.style.background = 'transparent';
      el.style.color = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)';
    }}
  >
    {icon}
  </button>
);

// ── Inner (внутри ReactFlowProvider) ─────────────────────────────────────────

interface GraphInnerProps {
  steps: StepResponse[];
  selectedStepId: number | null;
  onNodeSelect: (stepId: number | null) => void;
  isDark: boolean;
  height: number | string;
}

const GraphInner: React.FC<GraphInnerProps> = ({
  steps,
  selectedStepId,
  onNodeSelect,
  isDark,
  height,
}) => {
  const d = useEditorI18n();
  const [nodes, setNodes, onNodesChange] = useNodesState([] as Node[]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([] as Edge[]);
  const { fitView } = useReactFlow();
  const containerRef = useRef<HTMLDivElement>(null);

  // Шаги, отсортированные по orderIndex — мемоизировать чтобы избежать
  // лишних рендеров при объектной идентичности prop
  const sortedSteps = useMemo(
    () => [...steps].sort((a, b) => a.orderIndex - b.orderIndex),
    [steps],
  );

  // При изменении шагов или темы — пересчитываем граф
  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = convertStepsToFlow(sortedSteps, isDark);
    setNodes(newNodes);
    setEdges(newEdges);
    const timer = setTimeout(() => {
      void fitView({ padding: 0.3, duration: 300 });
    }, 60);
    return () => clearTimeout(timer);
  // setNodes/setEdges/fitView — стабильные ссылки, не нужны в deps
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortedSteps, isDark]);

  // При изменении выбранного шага — подсвечиваем узел
  useEffect(() => {
    setNodes(nds =>
      nds.map(n => {
        if (n.type !== 'stepNode' || !n.id.startsWith('step-')) return n;
        const orderIdx = parseInt(n.id.slice('step-'.length), 10);
        const stepForNode = sortedSteps.find(s => s.orderIndex === orderIdx);
        return { ...n, selected: stepForNode?.id === selectedStepId };
      }),
    );
  // setNodes — стабильная ссылка
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedStepId, sortedSteps]);

  const handleNodeClick = useCallback(
    (_e: React.MouseEvent, node: Node) => {
      if (node.type === 'stepNode' && node.id.startsWith('step-')) {
        const orderIdx = parseInt(node.id.slice('step-'.length), 10);
        const found = sortedSteps.find(s => s.orderIndex === orderIdx);
        onNodeSelect(found?.id ?? null);
      } else {
        onNodeSelect(null);
      }
    },
    [sortedSteps, onNodeSelect],
  );

  const handleAutoLayout = useCallback(() => {
    const { nodes: ln, edges: le } = getAutoLayout(nodes, edges);
    setNodes(ln);
    setEdges(le);
    setTimeout(() => { void fitView({ padding: 0.3, duration: 380 }); }, 80);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, edges]);

  const handleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen?.().catch(() => undefined);
    } else {
      document.exitFullscreen?.().catch(() => undefined);
    }
  }, []);

  const bgColor   = isDark ? '#1e1e1e' : '#f5f5f7';
  const dotColor  = isDark ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.08)';
  const panelBg   = isDark ? '#2c2c2e' : '#ffffff';
  const panelBd   = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';
  const miniStyle: React.CSSProperties = isDark
    ? { background: '#2c2c2e', border: '1px solid rgba(255,255,255,0.12)', borderRadius: 10, width: 110, height: 80 }
    : { background: '#fff', border: '1px solid rgba(0,0,0,0.10)', borderRadius: 10, width: 110, height: 80 };

  return (
    <div ref={containerRef} style={{ width: '100%', height }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodeClick={handleNodeClick}
        onPaneClick={() => onNodeSelect(null)}
        nodesDraggable
        nodesConnectable={false}
        elementsSelectable
        panOnDrag
        zoomOnScroll
        fitView
        fitViewOptions={{ padding: 0.3 }}
        proOptions={{ hideAttribution: true }}
        minZoom={0.10}
        maxZoom={3}
        style={{ background: bgColor }}
      >
        <Background
          variant={BackgroundVariant.Dots}
          gap={24}
          size={1.5}
          color={dotColor}
        />

        <Controls showInteractive={false} />

        <Panel position="top-right">
          <div style={{
            display: 'flex',
            gap: 4,
            padding: '5px 6px',
            background: panelBg,
            border: `1px solid ${panelBd}`,
            borderRadius: 10,
            boxShadow: isDark ? '0 1px 4px rgba(0,0,0,0.24)' : '0 1px 4px rgba(0,0,0,0.08)',
          }}>
            <PanelBtn
              icon={<AimOutlined />}
              title={d.centerGraph}
              onClick={() => { void fitView({ padding: 0.3, duration: 350 }); }}
              isDark={isDark}
            />
            <PanelBtn
              icon={<ReloadOutlined />}
              title={d.autoLayout}
              onClick={handleAutoLayout}
              isDark={isDark}
            />
            <PanelBtn
              icon={<FullscreenOutlined />}
              title={d.fullscreen}
              onClick={handleFullscreen}
              isDark={isDark}
            />
          </div>
        </Panel>

        {nodes.length >= 4 && (
          <MiniMap
            style={miniStyle}
            maskColor={isDark ? 'rgba(30,30,30,0.65)' : 'rgba(245,245,247,0.72)'}
            nodeColor={n => {
              const t = (n.data as Record<string, unknown>).stepType as string | undefined;
              return t ? getTypeAccent(t, isDark) : (isDark ? '#3a3a3c' : '#d1d1d6');
            }}
            zoomable={false}
            pannable={false}
          />
        )}
      </ReactFlow>
    </div>
  );
};

// ── Публичный компонент (добавляет ReactFlowProvider) ─────────────────────────

export interface SequenceEditorGraphProps {
  steps: StepResponse[];
  selectedStepId: number | null;
  onNodeSelect: (stepId: number | null) => void;
  height?: number | string;
}

export const SequenceEditorGraph: React.FC<SequenceEditorGraphProps> = ({
  steps,
  selectedStepId,
  onNodeSelect,
  height = 520,
}) => {
  const { isDark } = useTheme();

  return (
    <ReactFlowProvider>
      <GraphInner
        steps={steps}
        selectedStepId={selectedStepId}
        onNodeSelect={onNodeSelect}
        isDark={isDark}
        height={height}
      />
    </ReactFlowProvider>
  );
};
