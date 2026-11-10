/// <reference lib="webworker" />
import dagre from '@dagrejs/dagre';
import { dagreLayout } from './algorithms';
import type { LayoutEdge, LayoutNode } from './engine';

self.onmessage = (event: MessageEvent<{ nodes: LayoutNode[]; edges: LayoutEdge[] }>) => {
  try {
    self.postMessage({ positions: dagreLayout(dagre, event.data.nodes, event.data.edges) });
  } catch (error) {
    self.postMessage({ error: String(error) });
  }
};
