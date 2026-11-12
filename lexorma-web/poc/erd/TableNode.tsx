import { createContext, Fragment, memo, useContext } from 'react';
import { BaseEdge, Handle, Position, getSmoothStepPath, useStore, type EdgeProps, type NodeProps } from '@xyflow/react';
import { nodeHeight } from './model';
import { rowCenterY, type RelationFlowEdge, type TableFlowNode } from './graph';

/** 노드 전체에 걸린 표시 옵션 - data 에 넣으면 옵션을 바꿀 때 모든 노드 객체를 새로 만들어야 해서 컨텍스트로 둔다 */
export const ErdOptionsContext = createContext({ simplifyBelowZoom: 0 });

/**
 * 테이블 노드: 헤더(스키마·테이블명) + 컬럼 행(PK/FK, 이름, 타입, NULL).
 * 축소 배율이 기준보다 작으면 컬럼 행을 그리지 않는다 (크기·Handle 위치는 같아서 관계선은 그대로 붙어 있다).
 */
export const TableNode = memo(function TableNode({ data, selected }: NodeProps<TableFlowNode>) {
  const { simplifyBelowZoom } = useContext(ErdOptionsContext);
  // 배율 자체가 아니라 '기준 미만 여부'만 구독 → 줌 중에는 기준을 넘을 때만 다시 그린다
  const simple = useStore((s) => s.transform[2] < simplifyBelowZoom);
  const { table, rows, fkIds, linkedIds } = data;

  return (
    <div className={`erd-table${selected ? ' is-selected' : ''}${simple ? ' is-simple' : ''}`} style={{ height: nodeHeight(rows.length) }}>
      <div className="erd-table__header" title={`${table.schema}.${table.name}`}>
        <span className="erd-table__schema">{table.schema}</span>
        <span className="erd-table__name">{table.name}</span>
      </div>
      {!simple && rows.map((c) => {
        const fk = fkIds.has(c.id);
        return (
          <div className="erd-col" key={c.id}>
            <span className={`erd-col__key${c.pk ? ' is-pk' : fk ? ' is-fk' : ''}`}>{c.pk && fk ? 'PF' : c.pk ? 'PK' : fk ? 'FK' : ''}</span>
            <span className="erd-col__name">{c.name}</span>
            <span className="erd-col__type">{c.dataType}</span>
            <span className="erd-col__null">{c.nullable ? 'NULL' : 'NN'}</span>
          </div>
        );
      })}
      {rows.map((c, i) => linkedIds.has(c.id) && (
        <Fragment key={c.id}>
          <Handle type="source" id={`${c.id}:L`} position={Position.Left} style={{ top: rowCenterY(i) }} isConnectable={false} />
          <Handle type="source" id={`${c.id}:R`} position={Position.Right} style={{ top: rowCenterY(i) }} isConnectable={false} />
        </Fragment>
      ))}
    </div>
  );
});

/** 관계선: 유형별 색·선 모양은 CSS(erd-edge--*), 자기 참조는 오른쪽으로 돌아 나오는 고리 */
export const RelationEdge = memo(function RelationEdge(props: EdgeProps<RelationFlowEdge>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props;
  const path = data?.self
    ? `M ${sourceX} ${sourceY} H ${sourceX + 24 + data.pairIndex * 8} V ${targetY} H ${targetX}`
    : getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, borderRadius: 6 })[0];
  const kind = data?.kind.toLowerCase().replace('_', '-');
  return <BaseEdge id={id} path={path} className={`erd-edge erd-edge--${kind}${data && data.pairCount > 1 ? ' is-composite' : ''}`} />;
});
