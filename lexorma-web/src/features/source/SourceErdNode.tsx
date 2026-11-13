import { Fragment, memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, ROW_HEIGHT } from '../erd/model';
import { useSimpleNodes } from '../erd/TableNode';
import { t } from '../../lib/i18n';
import type { SourceNode } from './sourceErd';

/** 테이블 연결점 : 왼쪽(참조됨) / 오른쪽(참조함) / 오른쪽 들어옴(자기 참조) */
export const SOURCE_TABLE_HANDLE = { in: 'table-in', out: 'table-out', self: 'table-self' } as const;
/** 컬럼 연결점 id : 행 번호로 만든다 (원본 이름에는 아무 문자나 올 수 있다) */
export const sourceColumnHandle = (row: number, side: 'in' | 'out' | 'self') => `c${row}-${side}`;

/**
 * 원본 DB ERD 의 테이블·VIEW 노드 (읽기 전용). 크기·모양은 물리 ERD 노드(erd.css 의 erd-table)와 같고 데이터는 원본 카탈로그다 :
 * 논리명·승인 상태가 없고, 부제는 원본 COMMENT 다. 컬럼은 최대 MAX_ROWS 행 (나머지는 상세 패널), 축소하면 컬럼을 생략한다 (연결점은 테이블 쪽으로).
 */
export const SourceErdNode = memo(function SourceErdNode({ data, selected }: NodeProps<SourceNode>) {
  const simple = useSimpleNodes();
  const { table, schemaName, showSchema, linkedColumns, hiddenRelations, related } = data;
  const view = table.type === 'VIEW';
  const rows = table.columns.slice(0, MAX_ROWS);
  const rowTop = (i: number) => HEADER_HEIGHT + i * ROW_HEIGHT + ROW_HEIGHT / 2;
  const subtitle = view ? t('VIEW · 정의 SQL 미수집') : table.comment || t('원본 COMMENT 없음');

  return (
    <div className={`erd-table${view ? ' erd-view' : ''}${selected ? ' selected' : ''}${related ? ' source-erd-related' : ''}`}
      style={{ width: NODE_WIDTH, height: nodeHeight(table.columns.length) }}>
      <header title={`${schemaName}.${table.name}${table.comment ? ` · ${table.comment}` : ''}`}>
        {view && <em className="erd-view-badge">VIEW</em>}
        <strong>{showSchema && <small className="erd-schema-prefix">{schemaName}.</small>}{table.name}</strong>
        <span>{subtitle}{hiddenRelations ? t(' · 화면 밖 FK {0}', hiddenRelations) : ''}</span>
      </header>
      {!simple && rows.map((c) => {
        const key = c.pk && c.fk ? 'PF' : c.pk ? 'PK' : c.fk ? 'FK' : '';
        return (
          <div className="erd-column" key={c.compareName} title={`${c.name} · ${c.dataType}${c.nullable === false ? ' · NOT NULL' : ''}`}>
            <b>{key}</b>
            <span>{c.name}</span>
            <small>{c.dataType}</small>
            <small>{c.nullable === false ? 'NN' : ''}</small>
          </div>
        );
      })}
      {!simple && table.columns.length > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 컬럼 · 상세 패널에서 확인', table.columns.length - MAX_ROWS)}</div>}

      <Handle type="target" id={SOURCE_TABLE_HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="source" id={SOURCE_TABLE_HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="target" id={SOURCE_TABLE_HANDLE.self} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      {!simple && rows.map((c, i) => linkedColumns.has(c.compareName) && (
        <Fragment key={c.compareName}>
          <Handle type="target" id={sourceColumnHandle(i, 'in')} position={Position.Left} style={{ top: rowTop(i) }} isConnectable={false} />
          <Handle type="source" id={sourceColumnHandle(i, 'out')} position={Position.Right} style={{ top: rowTop(i) }} isConnectable={false} />
          <Handle type="target" id={sourceColumnHandle(i, 'self')} position={Position.Right} style={{ top: rowTop(i) }} isConnectable={false} />
        </Fragment>
      ))}
    </div>
  );
});
