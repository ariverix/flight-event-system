import dagre from 'dagre';
import type { Node, Edge } from '@xyflow/react';

interface LayoutOpts {
  direction?: 'TB' | 'LR';
  nodeWidth?: number;
  nodeHeight?: number;
  nodeSep?: number;
  rankSep?: number;
}

export function getAutoLayout(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOpts = {},
): { nodes: Node[]; edges: Edge[] } {
  if (!nodes.length) return { nodes, edges };

  const {
    direction = 'TB',
    nodeWidth  = 185,
    nodeHeight = 76,
    nodeSep    = 70,
    rankSep    = 95,
  } = opts;

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: direction, nodesep: nodeSep, ranksep: rankSep });

  nodes.forEach(n => g.setNode(n.id, { width: nodeWidth, height: nodeHeight }));
  edges.forEach(e => g.setEdge(e.source, e.target));
  dagre.layout(g);

  return {
    nodes: nodes.map(n => {
      const gn = g.node(n.id);
      return { ...n, position: { x: gn.x - nodeWidth / 2, y: gn.y - nodeHeight / 2 } };
    }),
    edges,
  };
}
