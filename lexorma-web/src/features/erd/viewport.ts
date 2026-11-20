import { getViewportForBounds, type ReactFlowInstance } from '@xyflow/react';
import { motionMs } from '../../lib/motion';
import { NODE_WIDTH, nodeHeight, type XY } from './model';

/** 노드 하나가 차지하는 영역 */
export interface NodeBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 테이블·엔터티·VIEW 노드의 영역 : 좌표 + 고정 너비 + 행 수로 정한 높이 (노드를 그릴 때와 같은 계산) */
export const nodeBox = (position: XY, rows: number): NodeBox => ({ x: position.x, y: position.y, width: NODE_WIDTH, height: nodeHeight(rows) });

/**
 * 노드들이 모두 보이게 화면을 맞춘다 (논리 ERD D-52, 물리 ERD M-2 개선 2차 D-18).
 * 좌표·크기를 이미 알고 있으므로 React Flow 의 크기 측정(화면 밖 노드는 그리지 않아 측정되지 않는다)을 기다리지 않고 경계를 직접 계산한다.
 * 화면 위치만 바꾸고 좌표·배치는 바꾸지 않는다 (저장 대상 아님). 동작 줄이기 설정이면 바로 옮긴다. 맞출 노드·화면 크기가 없으면 아무것도 하지 않는다
 */
export function fitToBoxes(flow: Pick<ReactFlowInstance, 'setViewport'>, canvas: HTMLElement | null, boxes: NodeBox[], duration = motionMs(200)) {
  if (!canvas || !boxes.length || !canvas.clientWidth || !canvas.clientHeight) return false;
  const x = Math.min(...boxes.map((b) => b.x)), y = Math.min(...boxes.map((b) => b.y));
  const width = Math.max(...boxes.map((b) => b.x + b.width)) - x;
  const height = Math.max(...boxes.map((b) => b.y + b.height)) - y;
  void flow.setViewport(getViewportForBounds({ x, y, width, height }, canvas.clientWidth, canvas.clientHeight, 0.05, 1, 0.15), { duration });
  return true;
}
