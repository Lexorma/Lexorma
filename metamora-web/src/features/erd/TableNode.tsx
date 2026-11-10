import { Fragment, memo } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { columnType, HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, ROW_HEIGHT, SIMPLE_WIDTH_PX, type TableNode as TableNodeType } from './model';

/** 화면에서 노드가 작으면(축소) 컬럼을 생략한다. 노드 크기와 테이블 연결점은 그대로라 관계선이 끊기지 않는다 */
export const useSimpleNodes = () => useStore((s) => s.transform[2] * NODE_WIDTH < SIMPLE_WIDTH_PX);

/** 컬럼 연결점 id : 왼쪽(들어옴) / 오른쪽(나감) / 오른쪽 들어옴(자기 참조) */
export const columnHandle = (columnId: number, side: 'in' | 'out' | 'self') => `c${columnId}-${side}`;
export const TABLE_HANDLE = { in: 'table-in', out: 'table-out', self: 'table-self' } as const;

export const TableNode = memo(function TableNode({ data, selected }: NodeProps<TableNodeType>) {
  const simple = useSimpleNodes();
  const { table, columns } = data;
  const rows = columns.slice(0, MAX_ROWS);
  const rowTop = (i: number) => HEADER_HEIGHT + i * ROW_HEIGHT + ROW_HEIGHT / 2;

  return (
    <div className={`erd-table${selected ? ' selected' : ''}`} style={{ width: NODE_WIDTH, height: nodeHeight(columns.length) }}>
      <header title={table.tableName}>
        <strong>{table.tableName}</strong>
        <span>
          {table.tableKorName || '논리명 없음'}
          {table.isApproval ? '' : ' · 승인 대기'}
          {table.isMasterTable ? ' · 마스터' : ''}
        </span>
      </header>
      {!simple && rows.map((c) => {
        const key = c.isPk && data.fkColumns.has(c.id) ? 'PF' : c.isPk ? 'PK' : data.fkColumns.has(c.id) ? 'FK' : '';
        return (
          <div className="erd-column" key={c.id} title={`${c.termName || c.columnDesc || c.columnName} · ${columnType(c)}`}>
            <b>{key}</b>
            <span>{c.columnName}</span>
            <small>{columnType(c)}</small>
            <small>{c.isNullable === false || c.isPk ? 'NN' : ''}</small>
          </div>
        );
      })}
      {!simple && columns.length > MAX_ROWS && <div className="erd-column-more">외 {columns.length - MAX_ROWS}개 컬럼 · 상세 패널에서 확인</div>}

      <Handle type="target" id={TABLE_HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="source" id={TABLE_HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="target" id={TABLE_HANDLE.self} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      {!simple && rows.map((c, i) => data.linkedColumns.has(c.id) && (
        <Fragment key={c.id}>
          <Handle type="target" id={columnHandle(c.id, 'in')} position={Position.Left} style={{ top: rowTop(i) }} isConnectable={false} />
          <Handle type="source" id={columnHandle(c.id, 'out')} position={Position.Right} style={{ top: rowTop(i) }} isConnectable={false} />
          <Handle type="target" id={columnHandle(c.id, 'self')} position={Position.Right} style={{ top: rowTop(i) }} isConnectable={false} />
        </Fragment>
      ))}
    </div>
  );
});
