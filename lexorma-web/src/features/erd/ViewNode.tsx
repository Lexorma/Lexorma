import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import { HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight } from './model';
import { useSimpleNodes } from './TableNode';
import type { ErdView } from '../view/model';
import { t } from '../../lib/i18n';

/** 물리 ERD 의 VIEW 노드 (M-15) : 읽기 전용. 배치는 저장하지 않는다 (테이블 배치와 따로, 화면을 열 때마다 테이블 오른쪽에 놓는다) */
export type ViewNodeData = { view: ErdView; columns: ErdView['columns'] };
export type ViewNodeType = Node<ViewNodeData, 'view'>;

/** VIEW 노드 id : 테이블 노드(테이블 ID)와 겹치지 않게 접두어를 붙인다 */
export const viewNodeId = (viewId: number) => `v${viewId}`;
export const isViewNodeId = (id: string) => id.startsWith('v');

/** VIEW → 참조 객체 선 : VIEW 의 왼쪽(나감) → 테이블·VIEW 의 오른쪽(들어옴) */
export const VIEW_HANDLE = { out: 'view-out', in: 'view-in' } as const;

export const ViewNode = memo(function ViewNode({ data, selected }: NodeProps<ViewNodeType>) {
  const simple = useSimpleNodes();
  const { view, columns } = data;
  return (
    <div className={`erd-table erd-view${selected ? ' selected' : ''}`} style={{ width: NODE_WIDTH, height: nodeHeight(columns.length) }}>
      <header title={view.viewName}>
        <em className="erd-view-badge">VIEW</em>
        <strong>{view.viewName}</strong>
        <span>{view.viewKorName || t('논리명 없음')}{view.isApproval ? '' : t(' · 승인 대기')}</span>
      </header>
      {!simple && columns.slice(0, MAX_ROWS).map((c, i) => (
        <div className="erd-column" key={i} title={`${c.columnKorName || c.columnName}${c.dataType ? ` · ${c.dataType}` : ''}`}>
          <b />
          <span>{c.columnName}</span>
          <small>{c.dataType ?? ''}</small>
          <small />
        </div>
      ))}
      {!simple && columns.length > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 컬럼 · 상세 패널에서 확인', columns.length - MAX_ROWS)}</div>}
      <Handle type="source" id={VIEW_HANDLE.out} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="target" id={VIEW_HANDLE.in} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
    </div>
  );
});
