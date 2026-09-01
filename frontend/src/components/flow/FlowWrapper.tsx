import React, { useRef, useCallback, useState } from 'react';
import {
  ReactFlow, Background, BackgroundVariant, Controls, MiniMap, Panel,
  useReactFlow, ReactFlowProvider,
} from '@xyflow/react';
import type { Node, Edge, NodeTypes, EdgeTypes, OnNodesChange, OnEdgesChange } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AimOutlined, FullscreenOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTheme } from '../../context/ThemeContext';
import { getAutoLayout } from '../../utils/graphLayout';
import { getTypeAccent, getHandleColors } from '../../utils/stepTypeColors';
import { NodeDetailPanel } from './NodeDetailPanel';
import { LabeledEdge } from './LabeledEdge';
import { PanelBtn } from './PanelBtn';

const edgeTypes: EdgeTypes = { labeled: LabeledEdge };
// Module-scope, а не {} инлайном в рендере — React Flow ремаунтит все кастомные
// ноды, если ссылка на nodeTypes меняется на каждый рендер (см. её же warning).
const EMPTY_NODE_TYPES: NodeTypes = {};

export interface FlowWrapperProps {
  nodes: Node[];
  edges: Edge[];
  nodeTypes?: NodeTypes;
  onNodesChange?: OnNodesChange;
  onEdgesChange?: OnEdgesChange;
  height?: number | string;
  showMiniMap?: boolean;
  showAutoLayout?: boolean;
  readonly?: boolean;
  onNodeClick?: (node: Node) => void;
}

/* ── Inner graph (inside ReactFlowProvider) ───────── */
interface FlowInnerProps extends FlowWrapperProps {
  containerRef: React.RefObject<HTMLDivElement>;
  isDark: boolean;
  mergedNodeTypes: NodeTypes;
  onNodeSelect: (node: Node) => void;
}

const FlowInner: React.FC<FlowInnerProps> = ({
  nodes, edges,
  onNodesChange, onEdgesChange,
  showMiniMap = true,
  showAutoLayout = true,
  readonly = false,
  onNodeClick,
  onNodeSelect,
  containerRef,
  isDark,
  mergedNodeTypes,
}) => {
  const { fitView, setNodes, setEdges } = useReactFlow();

  const handleAutoLayout = useCallback(() => {
    const { nodes: ln, edges: le } = getAutoLayout(nodes, edges);
    setNodes(ln);
    setEdges(le);
    setTimeout(() => fitView({ padding: 0.3, duration: 380 }), 80);
  }, [nodes, edges, setNodes, setEdges, fitView]);

  const handleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen?.().catch(() => {});
    } else {
      document.exitFullscreen?.().catch(() => {});
    }
  }, [containerRef]);

  const bgColor  = isDark ? '#1e1e1e' : '#f5f5f7';
  const dotColor = isDark ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.08)';
  const panelBg  = isDark ? '#2c2c2e' : '#ffffff';
  const panelBd  = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';
  const miniMask = isDark ? 'rgba(30,30,30,0.65)' : 'rgba(245,245,247,0.72)';
  const miniStyle: React.CSSProperties = isDark
    ? { background: '#2c2c2e', border: '1px solid rgba(255,255,255,0.12)', borderRadius: 10, width: 110, height: 80 }
    : { background: '#fff', border: '1px solid rgba(0,0,0,0.10)', borderRadius: 10, width: 110, height: 80 };

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      nodeTypes={mergedNodeTypes}
      edgeTypes={edgeTypes}
      onNodeClick={(_, n) => {
        onNodeSelect(n);
        onNodeClick?.(n);
      }}
      nodesDraggable={!readonly}
      nodesConnectable={!readonly}
      elementsSelectable
      panOnDrag
      zoomOnScroll
      fitView
      fitViewOptions={{ padding: 0.3 }}
      proOptions={{ hideAttribution: true }}
      minZoom={0.10}
      maxZoom={3}
      style={{ background: bgColor, overflow: 'visible' }}
    >
      <Background variant={BackgroundVariant.Dots} gap={24} size={1.5} color={dotColor} />

      {/* Built-in zoom/pan controls */}
      <Controls showInteractive={false} />

      {/* Custom toolbar: centre · auto-layout · fullscreen */}
      <Panel position="top-right">
        <div style={{
          display: 'flex', gap: 4, padding: '5px 6px',
          background: panelBg,
          border: `1px solid ${panelBd}`,
          borderRadius: 10,
          boxShadow: isDark ? '0 1px 4px rgba(0,0,0,0.24)' : '0 1px 4px rgba(0,0,0,0.08)',
        }}>
          <PanelBtn
            icon={<AimOutlined />}
            title="Центрировать граф"
            onClick={() => fitView({ padding: 0.3, duration: 350 })}
            isDark={isDark}
          />
          {showAutoLayout && (
            <PanelBtn
              icon={<ReloadOutlined />}
              title="Авто-расстановка"
              onClick={handleAutoLayout}
              isDark={isDark}
            />
          )}
          <PanelBtn
            icon={<FullscreenOutlined />}
            title="Полный экран"
            onClick={handleFullscreen}
            isDark={isDark}
          />
        </div>
      </Panel>

      {showMiniMap && nodes.length >= 4 && (
        <MiniMap
          style={miniStyle}
          maskColor={miniMask}
          nodeColor={n => {
            const state = (n.data as Record<string, unknown>).state as string | undefined;
            if (state === 'success')   return isDark ? '#30d158' : '#34c759';
            if (state === 'failure')   return isDark ? '#ff453a' : '#ff3b30';
            if (state === 'active')    return isDark ? '#ff9f0a' : '#ff9500';
            if (state === 'unreached') return getHandleColors(isDark).background;
            const stepType = (n.data as Record<string, unknown>).stepType as string | undefined;
            return stepType ? getTypeAccent(stepType, isDark) : getHandleColors(isDark).background;
          }}
          zoomable={false}
          pannable={false}
        />
      )}
    </ReactFlow>
  );
};

/* ── Public wrapper: graph + detail panel ─────────── */
export const FlowWrapper: React.FC<FlowWrapperProps> = props => {
  const { isDark } = useTheme();
  const containerRef = useRef<HTMLDivElement>(null);
  const { height = 520, nodeTypes: customNodeTypes } = props;

  const [selectedNode, setSelectedNode] = useState<Node | null>(null);

  const mergedNodeTypes: NodeTypes = customNodeTypes ?? EMPTY_NODE_TYPES;

  const panelBorderColor = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';
  const panelBgColor     = isDark ? '#2c2c2e' : '#ffffff';

  return (
    <div
      ref={containerRef}
      className="flow-container"
      style={{
        display: 'flex',
        width: '100%',
        height,
        borderRadius: 12,
        overflow: 'hidden',
        position: 'relative',
      }}
    >
      {/* ── Graph (left, fills remaining space) ── */}
      <div style={{
        flex: selectedNode ? '0 0 66%' : '1',
        minWidth: 0,
        position: 'relative',
        overflow: 'visible',
        transition: 'flex 0.22s ease',
      }}>
        <ReactFlowProvider>
          <FlowInner
            {...props}
            containerRef={containerRef}
            isDark={isDark}
            mergedNodeTypes={mergedNodeTypes}
            onNodeSelect={setSelectedNode}
          />
        </ReactFlowProvider>
      </div>

      {/* ── Node detail panel (right, shown only when a node is selected) ── */}
      <div style={{
        width: selectedNode ? '34%' : 0,
        flexShrink: 0,
        borderLeft: selectedNode ? `1px solid ${panelBorderColor}` : 'none',
        background: panelBgColor,
        overflow: 'hidden',
        transition: 'width 0.22s ease',
      }}>
        <NodeDetailPanel
          selectedNode={selectedNode}
          onClose={() => setSelectedNode(null)}
          isDark={isDark}
        />
      </div>
    </div>
  );
};
