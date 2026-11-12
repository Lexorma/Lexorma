import { Fragment, memo } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { columnType, DIFF_LABEL, DIFF_MARK, diffClass, HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, ROW_HEIGHT, SIMPLE_WIDTH_PX, type DiffKind, type NodeDiff, type TableNode as TableNodeType } from './model';
import { t } from '../../lib/i18n';

/** 화면에서 노드가 작으면(축소) 컬럼을 생략한다. 노드 크기와 테이블 연결점은 그대로라 관계선이 끊기지 않는다 */
export const useSimpleNodes = () => useStore((s) => s.transform[2] * NODE_WIDTH < SIMPLE_WIDTH_PX);

/** 컬럼 연결점 id : 왼쪽(들어옴) / 오른쪽(나감) / 오른쪽 들어옴(자기 참조) */
export const columnHandle = (columnId: number, side: 'in' | 'out' | 'self') => `c${columnId}-${side}`;
export const TABLE_HANDLE = { in: 'table-in', out: 'table-out', self: 'table-self' } as const;

/** 버전 비교 ERD 의 변경 표시 (노드 오른쪽 위 : 기호 + 글자). 논리 ERD 의 엔터티 노드도 같이 쓴다 */
export function DiffBadge({ diff }: { diff?: NodeDiff }) {
  return diff?.change ? <em className={`erd-diff-badge${diffClass(diff.change)}`}>{DIFF_MARK[diff.change]} {DIFF_LABEL[diff.change]}</em> : null;
}

/** 행 이름 앞의 변경 기호 */
export function DiffMark({ change }: { change?: DiffKind }) {
  return change ? <i title={DIFF_LABEL[change]}>{DIFF_MARK[change]}</i> : null;
}

export const TableNode = memo(function TableNode({ data, selected, isConnectable }: NodeProps<TableNodeType>) {
  const simple = useSimpleNodes();
  const { table, columns, diff } = data;
  const rows = columns.slice(0, MAX_ROWS);
  const rowTop = (i: number) => HEADER_HEIGHT + i * ROW_HEIGHT + ROW_HEIGHT / 2;

  return (
    <div className={`erd-table${selected ? ' selected' : ''}${diffClass(diff?.change)}`} style={{ width: NODE_WIDTH, height: nodeHeight(columns.length) }}>
      <header title={table.tableName}>
        <DiffBadge diff={diff} />
        <strong>{table.tableName}</strong>
        <span>
          {diff?.caption ? `${diff.caption} · ` : ''}
          {table.tableKorName || t('논리명 없음')}
          {table.isApproval ? '' : t(' · 승인 대기')}
          {table.isMasterTable ? t(' · 마스터') : ''}
        </span>
      </header>
      {!simple && rows.map((c) => {
        const key = c.isPk && data.fkColumns.has(c.id) ? 'PF' : c.isPk ? 'PK' : data.fkColumns.has(c.id) ? 'FK' : '';
        return (
          <div className={`erd-column${diffClass(diff?.rows.get(c.id))}`} key={c.id} title={`${c.termName || c.columnDesc || c.columnName} · ${columnType(c)}`}>
            <b>{key}</b>
            <span><DiffMark change={diff?.rows.get(c.id)} />{c.columnName}</span>
            <small>{columnType(c)}</small>
            <small>{c.isNullable === false || c.isPk ? 'NN' : ''}</small>
          </div>
        );
      })}
      {!simple && columns.length > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 컬럼 · 상세 패널에서 확인', columns.length - MAX_ROWS)}</div>}

      {/* isConnectable 은 화면의 nodesConnectable(물리 ERD 편집 모드)이다 : 오른쪽 연결점(참조하는 쪽)을 끌어 왼쪽 연결점(참조되는 쪽)에 놓으면 FK 등록 대화상자가 열린다 */}
      <Handle type="target" id={TABLE_HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={isConnectable} />
      <Handle type="source" id={TABLE_HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={isConnectable} />
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
