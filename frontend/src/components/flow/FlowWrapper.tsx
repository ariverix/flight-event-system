import React, { useRef, useCallback, useState } from 'react';
import {
  ReactFlow, Background, BackgroundVariant, Controls, MiniMap, Panel,
  useReactFlow, ReactFlowProvider,
} from '@xyflow/react';
import type { Node, Edge, NodeTypes, EdgeTypes, OnNodesChange, OnEdgesChange } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AimOutlined, FullscreenOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTheme } from '../../context/ThemeContext';
import { flowNodeTypes } from './FlowNodes';
import { getAutoLayout } from '../../utils/graphLayout';
import { NodeDetailPanel } from './NodeDetailPanel';
import { LabeledEdge } from './LabeledEdge';

const edgeTypes: EdgeTypes = { labeled: LabeledEdge };

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

/* ── Toolbar button ───────────────────────────────── */
const PanelBtn: React.FC<{
  icon: React.ReactNode;
  title: string;
  onClick: () => void;
  isDark: boolean;
}> = ({ icon, title, onClick, isDark }) => (
  <button
    title={title}
    onClick={onClick}
    style={{
      background: 'transparent',
      border: 'none',
      color: isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)',
      width: 30, height: 30,
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
      el.style.color      = isDark ? 'rgba(255,255,255,0.90)' : 'rgba(0,0,0,0.80)';
    }}
    onMouseLeave={e => {
      const el = e.currentTarget as HTMLButtonElement;
      el.style.background = 'transparent';
      el.style.color      = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)';
    }}
  >
    {icon}
  </button>
);

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

  const bgColor  = isDark ? '#060910' : '#eef2ff';
  const dotColor = isDark ? 'rgba(255,255,255,0.09)' : 'rgba(0,0,0,0.07)';
  const panelBg  = isDark ? 'rgba(6,7,16,0.92)'      : 'rgba(255,255,255,0.96)';
  const panelBd  = isDark ? 'rgba(255,255,255,0.11)'  : 'rgba(0,0,0,0.10)';
  const miniMask = isDark ? 'rgba(4,5,8,0.65)'        : 'rgba(238,242,255,0.72)';
  const miniStyle: React.CSSProperties = isDark
    ? { background: 'rgba(4,5,8,0.90)', backdropFilter: 'blur(12px)', border: '1px solid rgba(255,255,255,0.11)', borderRadius: 12, width: 110, height: 80 }
    : { background: '#fff', border: '1px solid rgba(0,0,0,0.09)', borderRadius: 12, width: 110, height: 80 };

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
          backdropFilter: 'blur(18px)',
          WebkitBackdropFilter: 'blur(18px)',
          border: `1px solid ${panelBd}`,
          borderRadius: 12,
          boxShadow: '0 4px 16px rgba(0,0,0,0.18)',
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
            const s = (n.data as any)?.state;
            if (s === 'success')   return isDark ? '#3fb950' : '#22c55e';
            if (s === 'failure')   return isDark ? '#f85149' : '#ef4444';
            if (s === 'active')    return '#f59e0b';
            if (s === 'unreached') return isDark ? '#1e2a3a' : '#e0e7ff';
            const t = (n.data as any)?.stepType;
            if (t === 'ACTION')   return '#3b82f6';
            if (t === 'EVALUATE') return '#8b5cf6';
            if (t === 'WAIT')     return '#f59e0b';
            const fs = (n.data as any)?.status;
            if (fs === 'SUCCESS') return '#10b981';
            if (fs === 'FAILURE') return '#ef4444';
            if (fs === 'WAITING') return '#f59e0b';
            return isDark ? '#1e2a3a' : '#d0d7de';
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

  const mergedNodeTypes: NodeTypes = { ...flowNodeTypes, ...(customNodeTypes ?? {}) };

  const panelBorderColor = isDark ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.07)';
  const panelBgColor     = isDark ? 'rgba(6,7,16,0.62)'      : 'rgba(255,255,255,0.75)';

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
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
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
