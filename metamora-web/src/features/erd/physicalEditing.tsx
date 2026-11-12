import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { columnType, type ErdColumn, type ErdRelation, type ErdTable } from './model';

/**
 * 물리 ERD 편집 (M-8 3차) 의 대화상자·패널. 저장은 모두 기존 물리 모델 API(/api/physical)다 — 테이블 관리·DDL 생성이 같은 데이터를 읽는다.
 * 관계·인덱스·제약조건은 수정 API 가 없다 (서버 정책 : 삭제 후 다시 등록). 화면도 수정 버튼을 만들지 않는다.
 */

interface ColumnRef {
  columnId: number;
  columnName: string;
}

interface TableIndex {
  id: number;
  indexName: string;
  indexType: string | null;
  isUnique: boolean | null;
  columns: ColumnRef[];
  isApproval: boolean | null;
}

interface TableConstraint {
  id: number;
  constraintName: string;
  constraintType: 'UNIQUE' | 'CHECK';
  checkExpression: string | null;
  columns: ColumnRef[];
  isApproval: boolean | null;
}

/** GET /api/physical/table/{tableId} (PhysicalModelDto.TableModel) */
interface TableModel {
  tableId: number;
  tableName: string;
  schemaName: string | null;
  outgoingRelations: ErdRelation[];
  incomingRelations: ErdRelation[];
  indexes: TableIndex[];
  constraints: TableConstraint[];
  unresolvedReferences: string[];
}

const SOURCE_LABEL: Record<ErdRelation['relationSource'], string> = {
  USER: t('사용자 정의'),
  REF_COLUMN: t('참조 테이블명'),
  IMPORTED: t('가져옴'),
  AI_APPROVED: t('AI 추정 승인'),
};

const APPROVED_NOTE = t('승인된 항목은 승인취소 후에 삭제할 수 있습니다.');

// ------------------------------------------------------------------ FK 등록

interface Pair {
  source: string;
  target: string;
}

/**
 * FK(관계) 등록. 참조하는 테이블(FK 컬럼이 있는 쪽) → 참조되는 테이블, 컬럼 쌍을 순서대로 (복합 FK).
 * ERD 에서 연결점을 이어 열어도 끈 방향을 그대로 확정하지 않는다 — 여기서 방향을 확인·교체하고 컬럼 쌍을 고른다.
 * 참조되는 컬럼이 PK·고유인지, 타입이 같은지는 서버가 검사하지 않는다 → 저장을 막지 않고 알려 주기만 한다.
 */
export function RelationForm({ tables, columns, schemaName, initial, onSaved }: {
  /** 조회 범위의 테이블 (범위 밖 테이블은 고를 수 없다) */
  tables: ErdTable[];
  columns: ErdColumn[];
  schemaName: (schemaId: number) => string;
  initial: { sourceTableId?: number; targetTableId?: number };
  onSaved: (saved: ErdRelation) => void;
}) {
  const dialog = useDialog();
  const columnsOf = (tableId: string) => columns.filter((c) => String(c.tableId) === tableId);
  // 참조되는 테이블의 PK 컬럼 수만큼 행을 미리 만든다 (참조되는 쪽은 PK 로 채우고, 참조하는 쪽은 사용자가 고른다)
  const defaultPairs = (targetId: string): Pair[] => {
    const pk = columnsOf(targetId).filter((c) => c.isPk);
    return pk.length ? pk.map((c) => ({ source: '', target: String(c.id) })) : [{ source: '', target: '' }];
  };
  const [sourceId, setSourceId] = useState(String(initial.sourceTableId ?? ''));
  const [targetId, setTargetId] = useState(String(initial.targetTableId ?? ''));
  const [name, setName] = useState('');
  const [type, setType] = useState<'FK' | 'LOGICAL'>('FK');
  const [pairs, setPairs] = useState<Pair[]>(() => defaultPairs(String(initial.targetTableId ?? '')));

  // 테이블이나 방향이 바뀌면 컬럼 쌍을 버리고 다시 고르게 한다 (다른 테이블의 컬럼이 남지 않게)
  const changeSource = (id: string) => {
    setSourceId(id);
    setPairs(defaultPairs(targetId));
  };
  const changeTarget = (id: string) => {
    setTargetId(id);
    setPairs(defaultPairs(id));
  };
  const swap = () => {
    setSourceId(targetId);
    setTargetId(sourceId);
    setPairs(defaultPairs(sourceId));
  };
  const setPair = (index: number, side: keyof Pair, value: string) => setPairs(pairs.map((p, i) => (i === index ? { ...p, [side]: value } : p)));

  const sourceColumns = columnsOf(sourceId);
  const targetColumns = columnsOf(targetId);
  const find = (list: ErdColumn[], id: string) => list.find((c) => String(c.id) === id);
  const chosen = pairs.filter((p) => p.source && p.target).map((p) => ({ source: find(sourceColumns, p.source), target: find(targetColumns, p.target) }));
  const notes: string[] = [];
  // 저장할 때 서버가 검사한다 (FK : 참조되는 컬럼이 PK 전체 또는 UNIQUE 키 전체, 컬럼 쌍의 타입이 대상 DB 에서 FK 로 묶일 수 있는 조합).
  // 화면은 UNIQUE 제약·고유 인덱스와 DB 별 타입 규칙을 모두 알지 못하므로 미리 알려만 주고 막지 않는다. 논리 관계는 검사하지 않는다.
  if (type === 'FK') {
    if (chosen.some((p) => p.target && !p.target.isPk)) notes.push(t('참조되는 컬럼에 PK 가 아닌 컬럼이 있습니다. UNIQUE 키 전체가 아니면 FK 로 저장되지 않습니다.'));
    const targetPk = targetColumns.filter((c) => c.isPk);
    if (targetPk.length > 1 && chosen.length > 0 && chosen.every((p) => p.target?.isPk) && chosen.length < targetPk.length) {
      notes.push(t('참조되는 테이블은 복합 PK({0}) 입니다. PK 컬럼을 모두 지정해야 FK 로 저장됩니다.', targetPk.map((c) => c.columnName).join(', ')));
    }
    chosen.filter((p) => p.source && p.target && columnType(p.source).toUpperCase() !== columnType(p.target).toUpperCase())
      .forEach((p) => notes.push(t('{0} 과(와) {1} 의 데이터 타입이 다릅니다. 대상 DB 에서 FK 로 연결할 수 없는 조합이면 저장되지 않습니다. ({2} / {3})', p.source!.columnName, p.target!.columnName, columnType(p.source!) || '-', columnType(p.target!) || '-')));
  }

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!sourceId || !targetId) return dialog.alert(t('참조하는 테이블과 참조되는 테이블을 선택해주세요.'));
    if (pairs.some((p) => !p.source || !p.target)) return dialog.alert(t('컬럼 쌍마다 참조하는 컬럼과 참조되는 컬럼을 모두 선택해주세요.'));
    if (new Set(pairs.map((p) => p.source)).size !== pairs.length || new Set(pairs.map((p) => p.target)).size !== pairs.length) {
      return dialog.alert(t('같은 컬럼을 두 번 지정할 수 없습니다.'));
    }
    dialog.confirm(t('저장하시겠습니까?'), async () => {
      // 이름을 비우면 서버가 FK_{테이블명}_{첫 컬럼명} 으로 정한다 (DDL 의 FK 이름과 같은 규칙)
      const saved = await api<ErdRelation>('/api/physical/relation', {
        method: 'POST',
        body: {
          relationName: name.trim() || null, relationType: type, sourceTableId: Number(sourceId), targetTableId: Number(targetId),
          columns: pairs.map((p) => ({ sourceColumnId: Number(p.source), targetColumnId: Number(p.target) })),
        },
      });
      onSaved(saved);
    });
  };

  const tableSelect = (id: string, label: string, value: string, onChange: (value: string) => void) => (
    <div className="form-group">
      <label htmlFor={id}>{label}</label>
      <select className="form-control" id={id} required value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t('테이블 선택')}</option>
        {tables.map((table) => <option key={table.id} value={table.id}>{schemaName(table.schemaId)}.{table.tableName}{table.tableKorName ? ` (${table.tableKorName})` : ''}</option>)}
      </select>
    </div>
  );
  const columnSelect = (label: string, list: ErdColumn[], value: string, onChange: (value: string) => void) => (
    <select className="form-control" aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t('컬럼 선택')}</option>
      {list.map((c) => <option key={c.id} value={c.id}>{c.isPk ? 'PK ' : ''}{c.columnName}{columnType(c) ? ` · ${columnType(c)}` : ''}</option>)}
    </select>
  );

  return (
    <form className="edit-form erd-relation-form" onSubmit={onSubmit}>
      <div className="logical-form-pair">
        {tableSelect('relationSourceTable', t('참조하는 테이블 (FK 컬럼이 있는 쪽)'), sourceId, changeSource)}
        {tableSelect('relationTargetTable', t('참조되는 테이블'), targetId, changeTarget)}
      </div>
      <p className="form-help">
        {t('조회 범위에 있는 테이블만 고를 수 있습니다. 같은 테이블을 양쪽에 고르면 자기 참조입니다.')} <button type="button" className="logical-link" onClick={swap}>{t('방향 바꾸기')}</button>
      </p>

      <div className="form-group">
        <label>{t('컬럼 대응 (순서대로, 여러 쌍이면 복합 FK)')}</label>
        <ol className="erd-pair-list">
          {pairs.map((pair, index) => (
            <li key={index}>
              {columnSelect(t('참조하는 컬럼 {0}', index + 1), sourceColumns, pair.source, (value) => setPair(index, 'source', value))}
              <span aria-hidden>→</span>
              {columnSelect(t('참조되는 컬럼 {0}', index + 1), targetColumns, pair.target, (value) => setPair(index, 'target', value))}
              <button type="button" className="btn btn-sm" disabled={pairs.length === 1} onClick={() => setPairs(pairs.filter((_, i) => i !== index))}>{t('빼기')}</button>
            </li>
          ))}
        </ol>
        <button type="button" className="btn btn-sm" onClick={() => setPairs([...pairs, { source: '', target: '' }])}>{t('컬럼 쌍 추가')}</button>
      </div>
      {notes.length > 0 && <ul className="erd-form-notes" role="note">{notes.map((note) => <li key={note}>{note}</li>)}</ul>}

      <div className="logical-form-pair">
        <div className="form-group">
          <label htmlFor="relationName">{t('관계 이름')}</label>
          <input type="text" className="form-control" id="relationName" maxLength={128} placeholder={t('비우면 FK_테이블명_컬럼명')} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="relationType">{t('관계 유형')}</label>
          <select className="form-control" id="relationType" value={type} onChange={(e) => setType(e.target.value as 'FK' | 'LOGICAL')}>
            <option value="FK">{t('FK (DDL 에 FOREIGN KEY 로 생성)')}</option>
            <option value="LOGICAL">{t('논리 관계 (DDL 에 넣지 않음)')}</option>
          </select>
        </div>
      </div>
      <p className="form-help">{t('컬럼의 참조 테이블명으로 이미 관계가 정해진 컬럼은 여기서 등록할 수 없습니다. 등록한 관계는 수정할 수 없고, 삭제한 뒤 다시 등록합니다.')}</p>
      <button type="submit" className="btn btn-primary">{t('외래키 저장')}</button>
    </form>
  );
}

// ------------------------------------------------------------------ 관계 패널

/** 선택한 관계선의 상세. 편집 모드에서 삭제, 승인 권한자에게 승인·승인취소 */
export function RelationPanel({ relation: r, outOfScope, onTable, onApproval, onDelete }: {
  relation: ErdRelation;
  /** 상대 테이블이 조회 범위 밖인지 (선은 그리지 않는다) */
  outOfScope: boolean;
  onTable: (tableId: number) => void;
  onApproval?: () => void;
  /** 편집 모드에서만 */
  onDelete?: () => void;
}) {
  const derived = r.relationSource === 'REF_COLUMN';
  const blocked = derived ? t('컬럼의 참조 테이블명으로 만든 관계입니다. 컬럼 편집에서 참조 테이블을 비우면 없어집니다.') : r.isApproval ? APPROVED_NOTE : '';
  return (
    <>
      <h3><Mono>{r.relationName ?? t('이름 없는 관계')}</Mono></h3>
      <p>
        {r.isApproval ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>}
        {' '}<Label variant={r.relationType === 'FK' ? 'accent' : ''}>{r.relationType === 'FK' ? 'FK' : t('논리 관계')}</Label>
        {' '}<Label>{SOURCE_LABEL[r.relationSource]}</Label>
        {r.columns.length > 1 && <> <Label>{t('복합 ({0}쌍)', r.columns.length)}</Label></>}
      </p>
      {(onDelete || (onApproval && !derived)) && (
        <div className="erd-toolbar erd-links erd-edit-actions">
          {onDelete && <button type="button" className="btn btn-sm btn-danger" disabled={!!blocked} title={blocked || undefined} onClick={onDelete}>{t('관계 삭제')}</button>}
          {onApproval && !derived && <button type="button" className="btn btn-sm" onClick={onApproval}>{r.isApproval ? t('승인취소') : tc('버튼', '승인')}</button>}
        </div>
      )}
      <ul className="erd-list">
        <li>
          <button type="button" className="erd-list-button" title={t('테이블로 이동')} onClick={() => onTable(r.sourceTableId)}>
            <strong>{t('참조하는 테이블')} : <Mono>{r.sourceTableName}</Mono></strong>
            <small>{r.columns.map((p) => p.sourceColumnName).join(', ')}</small>
          </button>
        </li>
        <li>
          <button type="button" className="erd-list-button" title={t('테이블로 이동')} onClick={() => onTable(r.targetTableId)}>
            <strong>{t('참조되는 테이블')} : <Mono>{r.targetTableName}</Mono></strong>
            <small>{r.columns.map((p) => p.targetColumnName).join(', ')}</small>
          </button>
        </li>
      </ul>
      <h4>{t('컬럼 대응 {0}쌍', r.columns.length)}</h4>
      <ol className="erd-pairs">
        {r.columns.map((p, i) => <li key={i}><Mono>{r.sourceTableName}.{p.sourceColumnName}</Mono> → <Mono>{r.targetTableName}.{p.targetColumnName}</Mono></li>)}
      </ol>
      {outOfScope && <p className="erd-muted">{t('상대 테이블이 조회 범위 밖이라 선은 그리지 않습니다.')}</p>}
      <p className="erd-muted">{t('관계는 수정할 수 없습니다. 바꾸려면 삭제한 뒤 다시 등록합니다.')}</p>
    </>
  );
}

// ------------------------------------------------------------------ 인덱스 · 제약조건

type KeyKind = 'index' | 'constraint';

/**
 * 테이블의 인덱스·제약조건(UNIQUE·CHECK) 관리. PK 와 단일 컬럼의 고유값·인덱스는 컬럼 설정(컬럼 편집)이고, 여기는 이름 있는·복합 키다
 * (같은 구성을 두 곳에 두지 않는다 — 서버가 거절한다). editable 이 아니면 조회만 한다.
 */
export function TableKeys({ table, columns, editable, approvalAvailable, onChanged }: {
  table: ErdTable;
  columns: ErdColumn[];
  editable: boolean;
  approvalAvailable: boolean;
  /** 저장·삭제·승인 뒤 (ERD 다시 조회) */
  onChanged: () => void;
}) {
  const dialog = useDialog();
  const model = useQuery({
    queryKey: ['physical-table-model', table.id],
    queryFn: () => api<TableModel>(`/api/physical/table/${table.id}`, { silent: true }),
    gcTime: 0,
  });
  const changed = () => {
    void model.refetch();
    onChanged();
  };

  const remove = (kind: KeyKind, id: number, name: string) => {
    dialog.confirm(kind === 'index' ? t('{0} 인덱스를 삭제하시겠습니까?', name) : t('{0} 제약조건을 삭제하시겠습니까?', name), async () => {
      await api(`/api/physical/${kind}/${id}`, { method: 'DELETE' });
      changed();
    });
  };
  const toggleApproval = async (kind: KeyKind, id: number, approved: boolean) => {
    await api(`/api/physical/${kind}/${id}/approval/${!approved}`, { method: 'PATCH' });
    changed();
  };

  if (model.isError) return <p role="alert">{t('인덱스·제약조건을 불러오지 못했습니다.')} {model.error.message}</p>;
  if (!model.data) return <p role="status">{t('불러오는 중입니다.')}</p>;

  const pk = columns.filter((c) => c.isPk);
  const actions = (kind: KeyKind, id: number, name: string, approved: boolean) => (
    <td>
      <div className="logical-actions">
        {editable && <button type="button" className="btn btn-sm btn-danger" disabled={approved} title={approved ? APPROVED_NOTE : undefined} onClick={() => remove(kind, id, name)}>{t('삭제')}</button>}
        {approvalAvailable && <button type="button" className="btn btn-sm" onClick={() => void toggleApproval(kind, id, approved)}>{approved ? t('승인취소') : tc('버튼', '승인')}</button>}
      </div>
    </td>
  );
  const withActions = editable || approvalAvailable;
  const approval = (value: boolean | null) => (value ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>);

  return (
    <div className="erd-keys">
      <p className="form-help">
        {t('PK : {0}', pk.length ? pk.map((c) => c.columnName).join(', ') : t('없음'))}
        {' · '}{t('PK 와 단일 컬럼의 고유값·인덱스는 컬럼 편집에서 정합니다. 여기서는 이름 있는 인덱스와 복합 UNIQUE·CHECK 를 관리합니다.')}
      </p>

      <h4>{t('인덱스 ({0})', model.data.indexes.length)}</h4>
      {model.data.indexes.length === 0 ? <p className="gh-muted">{t('등록된 인덱스가 없습니다.')}</p> : (
        <div className="dict-detail-columns"><div className="table-wrap"><table>
          <thead><tr>{[t('인덱스명'), t('구분'), t('컬럼 (순서대로)'), t('승인 상태'), ...(withActions ? [t('관리')] : [])].map((h) => <th key={h}>{h}</th>)}</tr></thead>
          <tbody>
            {model.data.indexes.map((index) => (
              <tr key={index.id}>
                <td className="gh-mono">{index.indexName}</td>
                <td>{index.isUnique ? 'UNIQUE' : 'INDEX'}{index.indexType ? ` · ${index.indexType}` : ''}</td>
                <td className="gh-mono">{index.columns.map((c) => c.columnName).join(', ')}</td>
                <td>{approval(index.isApproval)}</td>
                {withActions && actions('index', index.id, index.indexName, !!index.isApproval)}
              </tr>
            ))}
          </tbody>
        </table></div></div>
      )}
      {editable && <KeyForm kind="index" tableId={table.id} columns={columns} onSaved={changed} />}

      <h4>{t('제약조건 ({0})', model.data.constraints.length)}</h4>
      {model.data.constraints.length === 0 ? <p className="gh-muted">{t('등록된 제약조건이 없습니다.')}</p> : (
        <div className="dict-detail-columns"><div className="table-wrap"><table>
          <thead><tr>{[t('제약조건명'), t('구분'), t('컬럼 / 조건식'), t('승인 상태'), ...(withActions ? [t('관리')] : [])].map((h) => <th key={h}>{h}</th>)}</tr></thead>
          <tbody>
            {model.data.constraints.map((constraint) => (
              <tr key={constraint.id}>
                <td className="gh-mono">{constraint.constraintName}</td>
                <td>{constraint.constraintType}</td>
                <td className="gh-mono">{constraint.constraintType === 'CHECK' ? constraint.checkExpression : constraint.columns.map((c) => c.columnName).join(', ')}</td>
                <td>{approval(constraint.isApproval)}</td>
                {withActions && actions('constraint', constraint.id, constraint.constraintName, !!constraint.isApproval)}
              </tr>
            ))}
          </tbody>
        </table></div></div>
      )}
      {editable && <KeyForm kind="constraint" tableId={table.id} columns={columns} onSaved={changed} />}

      {model.data.unresolvedReferences.length > 0 && (
        <p className="form-help">{t('참조 테이블명이 있지만 관계로 확정하지 못한 컬럼 : {0}', model.data.unresolvedReferences.join(', '))}</p>
      )}
    </div>
  );
}

/** 인덱스 / 제약조건 등록 (한 줄 폼). 컬럼은 고른 순서가 키 순서다. 이름을 비우면 서버가 규칙대로 정한다 */
function KeyForm({ kind, tableId, columns, onSaved }: { kind: KeyKind; tableId: number; columns: ErdColumn[]; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState('');
  const [type, setType] = useState(kind === 'index' ? 'INDEX' : 'UNIQUE');
  const [ids, setIds] = useState<number[]>([]);
  const [expression, setExpression] = useState('');
  const check = type === 'CHECK';
  const available = columns.filter((c) => !ids.includes(c.id));
  const columnName = new Map(columns.map((c) => [c.id, c.columnName]));

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (check ? !expression.trim() : ids.length === 0) return dialog.alert(check ? t('CHECK 조건식을 입력해주세요.') : t('컬럼을 1개 이상 선택해주세요.'));
    dialog.confirm(t('저장하시겠습니까?'), async () => {
      const refs = ids.map((columnId) => ({ columnId }));
      if (kind === 'index') {
        await api('/api/physical/index', { method: 'POST', body: { tableId, indexName: name.trim() || null, isUnique: type === 'UNIQUE', columns: refs } });
      } else {
        await api('/api/physical/constraint', {
          method: 'POST',
          body: { tableId, constraintName: name.trim() || null, constraintType: type, checkExpression: check ? expression.trim() : null, columns: check ? null : refs },
        });
      }
      setName('');
      setIds([]);
      setExpression('');
      onSaved();
    });
  };

  const id = `erdKey-${kind}`;
  return (
    <form className="erd-key-form" onSubmit={onSubmit}>
      <select className="form-control" aria-label={kind === 'index' ? t('인덱스 구분') : t('제약조건 구분')} value={type} onChange={(e) => { setType(e.target.value); setIds([]); }}>
        {kind === 'index'
          ? <><option value="INDEX">INDEX</option><option value="UNIQUE">UNIQUE INDEX</option></>
          : <><option value="UNIQUE">UNIQUE</option><option value="CHECK">CHECK</option></>}
      </select>
      {check ? (
        <input type="text" className="form-control erd-key-wide" aria-label={t('CHECK 조건식')} maxLength={1000} placeholder={t('조건식 (예 : AMT >= 0)')} value={expression} onChange={(e) => setExpression(e.target.value)} />
      ) : (
        <span className="erd-key-columns">
          {ids.map((columnId) => (
            <Label key={columnId}><span className="gh-mono">{columnName.get(columnId)}</span> <button type="button" className="logical-link" aria-label={t('빼기')} onClick={() => setIds(ids.filter((v) => v !== columnId))}>×</button></Label>
          ))}
          <select className="form-control" id={`${id}-column`} aria-label={t('컬럼 추가')} value="" disabled={!available.length} onChange={(e) => e.target.value && setIds([...ids, Number(e.target.value)])}>
            <option value="">{available.length ? t('컬럼을 골라 추가') : t('모든 컬럼을 추가했습니다')}</option>
            {available.map((c) => <option key={c.id} value={c.id}>{c.columnName}</option>)}
          </select>
        </span>
      )}
      <input type="text" className="form-control" id={`${id}-name`} aria-label={t('이름')} maxLength={128} placeholder={t('이름 (비우면 자동)')} value={name} onChange={(e) => setName(e.target.value)} />
      <button type="submit" className="btn btn-primary">{kind === 'index' ? t('인덱스 추가') : t('제약조건 추가')}</button>
    </form>
  );
}
