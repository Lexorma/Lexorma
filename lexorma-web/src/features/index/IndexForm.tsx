import { useEffect, useState, type FormEvent } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import type { Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { useSchemas } from '../schema/schemas';
import { definitionMembers, useIndexTypes, type IndexDetail, type IndexSaveResult } from './model';

interface ColumnOption {
  id: number;
  columnName: string;
  sortOrder: number | null;
  isPk: string;
}

/** 검색 키 한 줄 : columnId 가 있으면 컬럼, 없으면 표현식 */
interface KeyRow {
  columnId: number | null;
  expression: string;
  direction: 'ASC' | 'DESC';
  nulls: '' | 'FIRST' | 'LAST';
}

/** PostgreSQL 기준 (서버 검증과 같다) : 정렬은 BTREE 만, INCLUDE 는 BTREE·GIST·SPGIST 만, 다른 DBMS 는 고급 속성 없음 */
const SORTABLE = ['BTREE'];
const INCLUDABLE = ['BTREE', 'GIST', 'SPGIST'];

/** 테이블 검색 입력이 멈춘 뒤 조회한다 (컬럼 등록 화면과 같은 300ms) */
const SEARCH_DELAY = 300;

function moved<T>(list: T[], index: number, delta: number): T[] {
  const next = [...list];
  [next[index], next[index + delta]] = [next[index + delta], next[index]];
  return next;
}

/**
 * 인덱스 등록·수정. 화면 구성은 다른 등록 화면과 같다 (항목 라벨 위 입력, 아래 닫기·저장).
 * 검색 키는 컬럼 또는 표현식이고 표에서 순서·정렬·NULLS 를 정한다. 정렬·NULLS·INCLUDE·WHERE 조건은 PostgreSQL 에서만,
 * 유형에 따라 쓸 수 없는 설정은 막고 이유를 보인다 (서버가 같은 기준으로 거절한다). 수정할 때 테이블은 바꿀 수 없다.
 * 승인된 인덱스를 저장하면 변경 요청이 되고(승인 권한자는 바로 반영), 결과는 onSaved 로 알린다.
 */
export function IndexForm({ detail, initialTableId, onSaved, onCancel }: {
  detail?: IndexDetail;
  initialTableId?: number;
  onSaved: (result: IndexSaveResult | null) => void;
  onCancel: () => void;
}) {
  const dialog = useDialog();
  const { approvalAvailable } = useAuth();
  const { schemas } = useSchemas();
  const types = useIndexTypes();
  const editing = detail?.index;
  const [schemaId, setSchemaId] = useState(detail?.schemaId ? String(detail.schemaId) : '');
  const [tableQuery, setTableQuery] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [tableId, setTableId] = useState<number | null>(editing?.tableId ?? initialTableId ?? null);
  const [name, setName] = useState(editing?.indexName ?? '');
  const [type, setType] = useState(editing?.indexType ?? '');
  const [unique, setUnique] = useState(!!editing?.isUnique);
  const [keys, setKeys] = useState<KeyRow[]>(editing ? definitionMembers(editing).map((m) => ({
    columnId: m.columnId, expression: m.expression ?? '', direction: m.direction === 'DESC' ? 'DESC' : 'ASC', nulls: m.nulls ?? '',
  })) : []);
  const [includes, setIncludes] = useState<number[]>(editing?.includeColumns?.map((c) => c.columnId) ?? []);
  const [condition, setCondition] = useState(editing?.condition ?? '');
  const [saving, setSaving] = useState(false);

  // 테이블 검색 : 입력이 멈춘 뒤 한 번 조회한다. 응답 순서와 관계없이 마지막 검색어의 결과만 쓴다 (조회 키 = 검색어)
  useEffect(() => {
    const timer = setTimeout(() => setSearchQuery(tableQuery.trim()), SEARCH_DELAY);
    return () => clearTimeout(timer);
  }, [tableQuery]);

  const tables = useQuery({
    queryKey: ['index-form-tables', schemaId, searchQuery],
    queryFn: () => api<{ data: Row[] }>(`/api/selectTable/data?page=0&size=100&sort=tableName,asc${schemaId ? `&schemaId=${enc(schemaId)}` : ''}`
      + `${searchQuery ? `&searchColumn=tableName&searchQuery=${enc(searchQuery)}` : ''}`),
    enabled: !editing,
    placeholderData: keepPreviousData,
  });
  const columns = useQuery({
    queryKey: ['index-form-columns', tableId],
    queryFn: async () => {
      const response = await api<{ columnInfos?: ColumnOption[] }>(`/api/selectColumn/tableColumn/${enc(tableId)}`);
      return [...(response.columnInfos ?? [])].sort((a, b) => (a.sortOrder ?? Number.MAX_SAFE_INTEGER) - (b.sortOrder ?? Number.MAX_SAFE_INTEGER));
    },
    enabled: tableId != null,
  });

  const columnName = new Map((columns.data ?? []).map((c) => [c.id, c.columnName]));
  editing?.members?.forEach((m) => { if (m.columnId != null && m.columnName && !columnName.has(m.columnId)) columnName.set(m.columnId, m.columnName); });
  editing?.includeColumns?.forEach((c) => { if (c.columnName && !columnName.has(c.columnId)) columnName.set(c.columnId, c.columnName); });
  const keyColumnIds = keys.map((k) => k.columnId).filter((id): id is number => id != null);
  // 키·INCLUDE 에 이미 쓴 컬럼은 다시 고를 수 없다 (같은 컬럼을 두 번, 또는 키와 INCLUDE 에 함께 두지 않는다)
  const available = (columns.data ?? []).filter((c) => !keyColumnIds.includes(c.id) && !includes.includes(c.id));

  // 프로젝트 DBMS·유형으로 쓸 수 있는 설정
  const postgres = types.data?.dbType === 'POSTGRESQL';
  const method = type || 'BTREE';
  const sortOk = postgres && SORTABLE.includes(method);
  const includeOk = postgres && INCLUDABLE.includes(method);
  const problems = [
    !postgres && (keys.some((k) => k.columnId == null) || condition.trim()) && t('{0} 에서는 표현식 키와 WHERE 조건을 쓸 수 없습니다.', types.data?.dbLabel ?? ''),
    !sortOk && keys.some((k) => k.direction === 'DESC' || k.nulls) && t('이 유형에서는 정렬(DESC·NULLS)을 쓸 수 없습니다. (PostgreSQL BTREE 만)'),
    !includeOk && includes.length > 0 && t('이 유형에서는 INCLUDE 를 쓸 수 없습니다. (PostgreSQL BTREE·GIST·SPGIST 만)'),
  ].filter((p): p is string => !!p);

  const setKey = (index: number, patch: Partial<KeyRow>) => setKeys(keys.map((k, i) => (i === index ? { ...k, ...patch } : k)));
  const resetTable = (id: number | null) => {
    setTableId(id);
    setKeys([]);
    setIncludes([]);
  };

  const approved = !!editing?.isApproval;
  const requestOnly = approved && !approvalAvailable; // 저장하면 변경 요청 (승인 대기)
  const submitLabel = !editing ? t('등록') : requestOnly ? t('변경 요청') : t('저장');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    if (tableId == null) return dialog.alert(t('테이블을 선택해주세요.'));
    if (keys.length === 0) return dialog.alert(t('컬럼을 1개 이상 선택해주세요.'));
    if (keys.some((k) => k.columnId == null && !k.expression.trim())) return dialog.alert(t('표현식을 입력해주세요.'));
    if (problems.length) return dialog.alert(problems.join('\n'));
    const message = requestOnly
      ? t('승인된 인덱스입니다. 저장하면 변경 요청이 만들어지고, 승인되기 전까지 지금 정의가 유지됩니다. 요청하시겠습니까?')
      : t('저장하시겠습니까?');
    dialog.confirm(message, async () => {
      const body = {
        tableId,
        indexName: name.trim() || null,
        indexType: type || null,
        isUnique: unique,
        members: keys.map((k) => ({ columnId: k.columnId, expression: k.columnId == null ? k.expression.trim() : null, direction: k.direction, nulls: k.nulls || null })),
        includeColumns: includes.map((columnId) => ({ columnId })),
        condition: condition.trim() || null,
      };
      setSaving(true);
      try {
        if (editing) {
          onSaved(await api<IndexSaveResult>(`/api/physical/index/${enc(editing.id)}`, { method: 'PUT', body }));
        } else {
          await api('/api/physical/index', { method: 'POST', body });
          onSaved(null);
        }
      } finally {
        setSaving(false);
      }
    });
  };

  return (
    <form className="edit-form index-form" onSubmit={onSubmit}>
      {editing && (
        <div className="dict-detail-status">
          <Label variant={approved ? 'success' : 'attention'} dot>{approved ? t('승인') : t('승인 대기')}</Label>
          <span className="gh-muted">
            {!approved ? t('승인 전 인덱스는 바로 수정됩니다.')
              : approvalAvailable ? t('승인된 인덱스입니다. 승인 권한이 있어 저장하면 바로 반영되고 변경 요청 이력이 남습니다.')
              : t('승인된 인덱스입니다. 저장하면 변경 요청이 되고 승인 후 반영됩니다.')}
          </span>
        </div>
      )}

      {editing ? (
        <div className="form-group">
          <label htmlFor="indexFormTable">{t('대상 테이블')}</label>
          <input type="text" className="form-control gh-mono" id="indexFormTable" readOnly value={`${detail?.schemaName ? `${detail.schemaName}.` : ''}${detail?.tableName ?? ''}`} />
        </div>
      ) : (
        <>
          <div className="form-group">
            <label htmlFor="indexFormSchema">{t('스키마')}</label>
            <select id="indexFormSchema" className="form-control" value={schemaId} onChange={(e) => { setSchemaId(e.target.value); resetTable(null); }}>
              <option value="">{t('전체 스키마')}</option>
              {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
            </select>
          </div>
          <div className="form-group">
            <label htmlFor="indexFormTable">{t('대상 테이블')}</label>
            <div className="index-form-row">
              <input type="search" className="form-control" aria-label={t('테이블명 검색')} placeholder={t('테이블명 검색')} value={tableQuery} onChange={(e) => setTableQuery(e.target.value)} />
              <select id="indexFormTable" className="form-control" value={tableId ?? ''} onChange={(e) => resetTable(e.target.value ? Number(e.target.value) : null)}>
                <option value="">{t('테이블 선택')}</option>
                {tableId != null && !(tables.data?.data ?? []).some((row) => row.id === tableId) && <option value={tableId}>#{tableId}</option>}
                {(tables.data?.data ?? []).map((row) => <option key={row.id} value={row.id}>{row.schemaName ? `${row.schemaName}.` : ''}{row.tableName}</option>)}
              </select>
            </div>
          </div>
        </>
      )}

      <div className="index-form-row">
        <div className="form-group">
          <label htmlFor="indexFormName">{t('인덱스명')}</label>
          <input id="indexFormName" type="text" className="form-control gh-mono" maxLength={128} placeholder={t('이름 (비우면 자동)')} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="indexFormType">{t('인덱스 유형')}</label>
          <select id="indexFormType" className="form-control" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">{t('기본 (DBMS 기본 방식)')}</option>
            {type && !(types.data?.types ?? []).includes(type) && <option value={type}>{type} ({t('지원하지 않음')})</option>}
            {(types.data?.types ?? []).map((v) => <option key={v} value={v}>{v}</option>)}
          </select>
          {types.data && <p className="form-help">{t('{0} 기준 지원 유형입니다.', types.data.dbLabel)}</p>}
        </div>
      </div>
      <div className="form-check">
        <input type="checkbox" className="form-check-input" id="indexFormUnique" checked={unique} onChange={(e) => setUnique(e.target.checked)} />
        <label className="form-check-label" htmlFor="indexFormUnique">UNIQUE</label>
      </div>

      <div className="dict-detail-columns index-editor">
        <h4>{t('검색 키 (순서대로)')}</h4>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>{['#', t('컬럼 / 표현식'), t('정렬'), 'NULLS', t('순서'), ''].map((h, i) => <th key={i}>{h}</th>)}</tr>
            </thead>
            <tbody>
              {keys.length === 0 && <tr><td colSpan={6} className="gh-muted">{t('컬럼을 골라 추가해주세요.')}</td></tr>}
              {keys.map((key, index) => (
                <tr key={index}>
                  <td className="text-center">{index + 1}</td>
                  <td className="index-editor-main">
                    {key.columnId != null
                      ? <span className="gh-mono">{columnName.get(key.columnId) ?? `#${key.columnId}`}</span>
                      : <input type="text" className="form-control gh-mono" aria-label={t('표현식 {0}', index + 1)} maxLength={1000}
                          placeholder={t('표현식 (예: lower(EMAIL))')} value={key.expression} onChange={(e) => setKey(index, { expression: e.target.value })} />}
                  </td>
                  <td>
                    <select className="form-control" aria-label={t('정렬 방향 {0}', index + 1)} value={key.direction} disabled={!sortOk && key.direction === 'ASC'}
                      onChange={(e) => setKey(index, { direction: e.target.value as KeyRow['direction'] })}>
                      <option value="ASC">ASC</option>
                      <option value="DESC">DESC</option>
                    </select>
                  </td>
                  <td>
                    <select className="form-control" aria-label={t('NULLS 위치 {0}', index + 1)} value={key.nulls} disabled={!sortOk && !key.nulls}
                      onChange={(e) => setKey(index, { nulls: e.target.value as KeyRow['nulls'] })}>
                      <option value="">{t('기본')}</option>
                      <option value="FIRST">FIRST</option>
                      <option value="LAST">LAST</option>
                    </select>
                  </td>
                  <td className="index-editor-order">
                    <button type="button" className="btn btn-sm" aria-label={t('위로')} disabled={index === 0} onClick={() => setKeys(moved(keys, index, -1))}>↑</button>
                    <button type="button" className="btn btn-sm" aria-label={t('아래로')} disabled={index === keys.length - 1} onClick={() => setKeys(moved(keys, index, 1))}>↓</button>
                  </td>
                  <td><button type="button" className="btn btn-sm" onClick={() => setKeys(keys.filter((_, i) => i !== index))}>{t('빼기')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="index-editor-add">
          <select className="form-control" aria-label={t('컬럼 추가')} value="" disabled={!available.length}
            onChange={(e) => e.target.value && setKeys([...keys, { columnId: Number(e.target.value), expression: '', direction: 'ASC', nulls: '' }])}>
            <option value="">{tableId == null ? t('테이블을 먼저 선택해주세요') : available.length ? t('컬럼을 골라 추가') : t('모든 컬럼을 추가했습니다')}</option>
            {available.map((c) => <option key={c.id} value={c.id}>{c.columnName}{c.isPk === 'Y' ? ' (PK)' : ''}</option>)}
          </select>
          <button type="button" className="btn" disabled={!postgres || tableId == null}
            title={!postgres ? t('{0} 에서는 표현식 키와 WHERE 조건을 쓸 수 없습니다.', types.data?.dbLabel ?? '') : undefined}
            onClick={() => setKeys([...keys, { columnId: null, expression: '', direction: 'ASC', nulls: '' }])}>{t('표현식 키 추가')}</button>
        </div>
      </div>

      <div className="dict-detail-columns index-editor">
        <h4>{t('INCLUDE 컬럼')} <span className="gh-muted">{t('검색 키가 아닌 포함 컬럼')}</span></h4>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>{['#', t('컬럼'), t('순서'), ''].map((h, i) => <th key={i}>{h}</th>)}</tr>
            </thead>
            <tbody>
              {includes.length === 0 && <tr><td colSpan={4} className="gh-muted">{t('INCLUDE 컬럼이 없습니다.')}</td></tr>}
              {includes.map((columnId, index) => (
                <tr key={columnId}>
                  <td className="text-center">{index + 1}</td>
                  <td className="index-editor-main gh-mono">{columnName.get(columnId) ?? `#${columnId}`}</td>
                  <td className="index-editor-order">
                    <button type="button" className="btn btn-sm" aria-label={t('위로')} disabled={index === 0} onClick={() => setIncludes(moved(includes, index, -1))}>↑</button>
                    <button type="button" className="btn btn-sm" aria-label={t('아래로')} disabled={index === includes.length - 1} onClick={() => setIncludes(moved(includes, index, 1))}>↓</button>
                  </td>
                  <td><button type="button" className="btn btn-sm" onClick={() => setIncludes(includes.filter((v) => v !== columnId))}>{t('빼기')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="index-editor-add">
          <select className="form-control" aria-label={t('INCLUDE 컬럼 추가')} value="" disabled={!includeOk || !available.length}
            onChange={(e) => e.target.value && setIncludes([...includes, Number(e.target.value)])}>
            <option value="">{includeOk ? (available.length ? t('컬럼을 골라 추가') : t('모든 컬럼을 추가했습니다')) : t('이 유형에서는 INCLUDE 를 쓸 수 없습니다. (PostgreSQL BTREE·GIST·SPGIST 만)')}</option>
            {available.map((c) => <option key={c.id} value={c.id}>{c.columnName}</option>)}
          </select>
        </div>
      </div>

      <div className="form-group index-form-condition">
        <label htmlFor="indexFormCondition">{t('WHERE 조건 (부분 인덱스)')}</label>
        <textarea id="indexFormCondition" className="form-control gh-mono" rows={3} maxLength={1000} disabled={!postgres && !condition}
          placeholder={t('예: ACTV_YN = true')} value={condition} onChange={(e) => setCondition(e.target.value)} />
        <p className="form-help">{t('표현식·조건은 실행하지 않고 형식(괄호·따옴표 짝, ; -- /* */ 금지)만 확인합니다. 실제 문법은 DDL 을 실행할 때 DB 가 확인합니다.')}</p>
      </div>

      {problems.length > 0 && <ul className="table-ddl-warnings" role="alert">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}

      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? t('저장 중…') : submitLabel}</button>
      </div>
    </form>
  );
}
