import { BaseEdge, getSmoothStepPath, type EdgeProps } from '@xyflow/react';
import { centered, endShift } from './relationLanes';
import type { RelationEdge } from './model';

/** 평행 관계선 : 테이블 연결점에 모이면 끝점을 헤더 안에서 대칭으로 벌리고(간격 8, ±20 — 헤더 높이 52 안), 꺾이는 세로 구간과 돌아 나가는 폭도 차선마다 달리한다 (논리 ERD 와 같은 값) */
const LANE_END_SPACING = 8;
const LANE_END_LIMIT = 20;
const LANE_CENTER_SPACING = 18;
const LANE_OFFSET = 20;
const LANE_OFFSET_STEP = 10;
/** 자기 참조 고리 : 관계마다 바깥으로 14씩 더 크게 (관계 ID 순) */
const SELF_LOOP_STEP = 14;

/**
 * 물리 FK 관계선 (M-2 개선 2차 D-07) : FK 제약조건 하나 = 선 하나. 대표 연결점(첫 컬럼 쌍, 안 보이면 테이블)에 붙고, 같은 두 테이블 사이의 FK 는
 * 관계 ID 순 차선(erd/relationLanes)으로 꺾이는 위치·폭을 달리하며 자기 참조는 테이블 오른쪽 바깥 고리로 그린다. 경로는 노드 좌표에서 매번 계산한다.
 * 끝 기호(markerStart = 자식 쪽, markerEnd = 부모 쪽)와 툴팁은 만드는 쪽(relationEdges)이 정한다. 선 위 글자(버전 비교의 추가·삭제·수정)는 React Flow 의 label 그대로
 */
export function FkEdge({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data, markerStart, markerEnd,
  label, labelStyle, labelShowBg, labelBgStyle, labelBgPadding, labelBgBorderRadius }: EdgeProps<RelationEdge>) {
  const lane = data?.lane ?? { index: 0, count: 1 };
  const self = !!data && data.relation.sourceTableId === data.relation.targetTableId;
  const shift = !self && data?.tableEnds ? endShift(lane, LANE_END_SPACING, LANE_END_LIMIT) : 0;
  const [path, labelX, labelY] = getSmoothStepPath(self
    ? { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, offset: LANE_OFFSET + lane.index * SELF_LOOP_STEP }
    : { sourceX, sourceY: sourceY + shift, targetX, targetY: targetY + shift, sourcePosition, targetPosition, offset: LANE_OFFSET + lane.index * LANE_OFFSET_STEP,
      centerX: (sourceX + targetX) / 2 + centered(lane) * LANE_CENTER_SPACING });
  return (
    <g className="erd-fk-edge">
      {data?.tooltip && <title>{data.tooltip}</title>}
      {/* 고르는 영역 : 선 양쪽 6px (평행 차선 간격보다 좁게 — 옆 관계를 대신 고르지 않는다) */}
      <BaseEdge id={id} path={path} style={style} markerStart={markerStart} markerEnd={markerEnd} interactionWidth={12}
        label={label} labelX={labelX} labelY={labelY} labelStyle={labelStyle} labelShowBg={labelShowBg} labelBgStyle={labelBgStyle}
        labelBgPadding={labelBgPadding} labelBgBorderRadius={labelBgBorderRadius} />
    </g>
  );
}

export const physicalEdgeTypes = { fk: FkEdge };
