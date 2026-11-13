import { useEffect, useState, type FormEvent } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { useSchemas } from '../schema/schemas';
import type { TableDdl } from '../table/TableDdl';
import { isInternal, referenceName, referenceTypeLabel, type ViewColumn, type ViewDetail, type ViewReference, type ViewSaveResult, type ViewTarget } from './model';

/** 참조 객체 검색 입력이 멈춘 뒤 조회한다 (인덱스 등록의 테이블 검색과 같은 300ms) */
const SEARCH_DELAY = 300;
const SQL_MAX = 100_000;

const emptyColumn = (): ViewColumn => ({ columnName: '', columnKorName: null, dataType: null, columnDescription: null });

function moved<T>(list: T[], index: number, delta: number): T[] {
  const next = [...list];
  [next[index], next[index + delta]] = [next[index + delta], next[index]];
  return next;
}

const refKey = (r: ViewReference) => (r.tableId != null ? `T${r.tableId}` : r.viewId != null ? `V${r.viewId}` : `X${r.referenceType}:${(r.schemaName ?? '').toUpperCase()}.${(r.objectName ?? '').toUpperCase()}`);

/**
 * VIEW 등록·수정 (M-15). 화면 구성은 인덱스 등록과 같다 (항목 라벨 위 입력, 표 편집, 아래 닫기·저장).
 * 정의 SQL 은 원문 그대로 저장하고 실행하지 않는다. 출력 컬럼·참조 객체는 사용자가 관리하는 정보이고 SQL 과 서로 자동으로 맞추지 않는다.
 * 승인된 VIEW 를 저장하면 변경 요청이 되고(승인 권한자는 바로 반영), 결과는 onSaved 로 알린다.
 */
export function ViewForm({ detail, onSaved, onCancel }: {
  detail?: ViewDetail;
  onSaved: (result: ViewSaveResult) => void;
  onCancel: () => void;
}) {
  const dialog = useDialog();
  const { approvalAvailable } = useAuth();
  const { schemas } = useSchemas();
  const editing = detail?.view;
  const [schemaId, setSchemaId] = useState(editing?.schemaId ? String(editing.schemaId) : '');
  const [name, setName] = useState(editing?.viewName ?? '');
  const [korName, setKorName] = useState(editing?.viewKorName ?? '');
  const [description, setDescription] = useState(editing?.viewDescription ?? '');
  const [sql, setSql] = useState(editing?.definition ?? '');
  const [columns, setColumns] = useState<ViewColumn[]>(editing?.columns ?? []);
  const [references, setReferences] = useState<ViewReference[]>(editing?.references ?? []);
  const [targetQuery, setTargetQuery] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [external, setExternal] = useState({ referenceType: 'TABLE' as ViewReference['referenceType'], schemaName: '', objectName: '' });
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState<{ ddl?: TableDdl; error?: string } | null>(null);

  // 참조 객체 후보 : 입력이 멈춘 뒤 한 번 조회 (조회 키 = 확정된 검색어라 늦게 온 이전 응답이 덮지 않는다)
  useEffect(() => {
    const timer = setTimeout(() => setSearchQuery(targetQuery.trim()), SEARCH_DELAY);
    return () => clearTimeout(timer);
  }, [targetQuery]);
  const targets = useQuery({
    queryKey: ['view-targets', searchQuery],
    queryFn: () => api<ViewTarget[]>(`/api/physical/view/targets?keyword=${enc(searchQuery)}`),
    placeholderData: keepPreviousData,
  });
  const used = new Set(references.map(refKey));
  const available = (targets.data ?? []).filter((target) => !(target.referenceType === 'VIEW' && target.id === detail?.id)
    && !used.has(target.referenceType === 'TABLE' ? `T${target.id}` : `V${target.id}`));

  const approved = !!detail?.isApproval;
  const requestOnly = approved && !approvalAvailable;
  const submitLabel = !editing ? t('등록') : requestOnly ? t('변경 요청') : t('저장');
  const sqlChanged = !!editing && sql !== editing.definition;

  const body = () => ({
    schemaId: schemaId ? Number(schemaId) : null,
    viewName: name.trim(),
    viewKorName: korName.trim() || null,
    viewDescription: description.trim() || null,
    definition: sql,
    columns: columns.map((c) => ({
      columnName: c.columnName.trim(),
      columnKorName: c.columnKorName?.trim() || null,
      dataType: c.dataType?.trim() || null,
      columnDescription: c.columnDescription?.trim() || null,
    })),
    references,
  });

  const problem = () => {
    if (!name.trim()) return t('VIEW명을 입력해주세요.');
    if (!sql.trim()) return t('정의 SQL 을 입력해주세요.');
    if (columns.some((c) => !c.columnName.trim())) return t('출력 컬럼명을 입력해주세요.');
    const names = columns.map((c) => c.columnName.trim().toUpperCase());
    const duplicated = names.find((n, i) => names.indexOf(n) !== i);
    if (duplicated) return t('출력 컬럼 {0} 이(가) 두 번 들어 있습니다.', duplicated);
    return null;
  };

  const showPreview = async () => {
    const message = problem();
    if (message) return dialog.alert(message);
    setPreview({});
    try {
      const ddl = await api<TableDdl>(`/api/physical/view/ddl/preview${detail ? `?viewId=${enc(detail.id)}` : ''}`, { method: 'POST', body: body(), silent: true });
      setPreview({ ddl });
    } catch (e) {
      setPreview({ error: e instanceof ApiError ? e.message : t('DDL 을 생성하지 못했습니다.') });
    }
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    const message = problem();
    if (message) return dialog.alert(message);
    const confirm = requestOnly
      ? t('승인된 VIEW 입니다. 저장하면 변경 요청이 만들어지고, 승인되기 전까지 지금 정의가 유지됩니다. 요청하시겠습니까?')
      : t('저장하시겠습니까?');
    dialog.confirm(confirm, async () => {
      setSaving(true);
      try {
        onSaved(editing
          ? await api<ViewSaveResult>(`/api/physical/view/${enc(detail!.id)}`, { method: 'PUT', body: body() })
          : await api<ViewSaveResult>('/api/physical/view', { method: 'POST', body: body() }));
      } finally {
        setSaving(false);
      }
    });
  };

  const setColumn = (index: number, patch: Partial<ViewColumn>) => setColumns(columns.map((c, i) => (i === index ? { ...c, ...patch } : c)));
  const addTarget = (value: string) => {
    const target = available.find((candidate) => `${candidate.referenceType}:${candidate.id}` === value);
    if (!target) return;
    setReferences([...references, target.referenceType === 'TABLE'
      ? { referenceType: 'TABLE', tableId: target.id, viewId: null, schemaName: target.schemaName, objectName: target.name }
      : { referenceType: 'VIEW', tableId: null, viewId: target.id, schemaName: target.schemaName, objectName: target.name }]);
  };
  const addExternal = () => {
    const objectName = external.objectName.trim();
    if (!objectName) return dialog.alert(t('객체명을 입력해주세요.'));
    const reference: ViewReference = { referenceType: external.referenceType, tableId: null, viewId: null, schemaName: external.schemaName.trim() || null, objectName };
    if (used.has(refKey(reference))) return dialog.alert(t('이미 추가한 참조 객체입니다.'));
    setReferences([...references, reference]);
    setExternal({ ...external, schemaName: '', objectName: '' });
  };

  return (
    <form className="edit-form index-form view-form" onSubmit={onSubmit}>
      {editing && (
        <div className="dict-detail-status">
          <Label variant={approved ? 'success' : 'attention'} dot>{approved ? t('승인') : t('승인 대기')}</Label>
          <span className="gh-muted">
            {!approved ? t('승인 전 VIEW 는 바로 수정됩니다.')
              : approvalAvailable ? t('승인된 VIEW 입니다. 승인 권한이 있어 저장하면 바로 반영되고 변경 요청 이력이 남습니다.')
              : t('승인된 VIEW 입니다. 저장하면 변경 요청이 되고 승인 후 기본 정보·정의 SQL·출력 컬럼·참조 객체가 한 번에 반영됩니다.')}
          </span>
        </div>
      )}

      <div className="dict-detail-columns">
        <h4>{t('기본 정보')}</h4>
        <div className="index-form-row">
          <div className="form-group">
            <label htmlFor="viewFormSchema">{t('스키마')}</label>
            <select id="viewFormSchema" className="form-control" value={schemas.find((s) => String(s.id) === schemaId)?.isDefault ? '' : schemaId} onChange={(e) => setSchemaId(e.target.value)}>
              <option value="">{t('기본 스키마')}</option>
              {schemas.filter((s) => !s.isDefault).map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
            </select>
          </div>
          <div className="form-group">
            <label htmlFor="viewFormName">{t('VIEW명')}</label>
            <input id="viewFormName" type="text" className="form-control gh-mono" maxLength={63} required placeholder={t('예: VW_ACTIVE_USER')} value={name} onChange={(e) => setName(e.target.value)} />
          </div>
        </div>
        <div className="index-form-row">
          <div className="form-group">
            <label htmlFor="viewFormKorName">{t('논리명')}</label>
            <input id="viewFormKorName" type="text" className="form-control" maxLength={255} value={korName} onChange={(e) => setKorName(e.target.value)} />
          </div>
          <div className="form-group">
            <label htmlFor="viewFormDescription">{t('설명')}</label>
            <input id="viewFormDescription" type="text" className="form-control" maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
        </div>
        <p className="form-help">{t('이름은 대문자로 저장합니다. 같은 프로젝트의 테이블과 같은 이름은 쓸 수 없습니다.')}</p>
      </div>

      <div className="dict-detail-columns">
        <h4>{t('정의 SQL')} <span className="gh-muted">{t('CREATE VIEW … AS 뒤의 조회 문')}</span></h4>
        <textarea id="viewFormSql" className="form-control gh-mono view-sql-input" aria-label={t('정의 SQL')} rows={12} maxLength={SQL_MAX} spellCheck={false}
          placeholder={'SELECT u.USER_ID, u.USER_NM\nFROM TB_USER u\nWHERE u.ACTV_YN = true'} value={sql} onChange={(e) => setSql(e.target.value)} />
        <p className="form-help">{t('VIEW 정의 SQL 은 실행하지 않습니다. 형식(문장 하나, SELECT·WITH 로 시작, 따옴표·괄호 짝, 조회가 아닌 명령)만 확인하며 실제 DBMS 적용 시 문법 및 참조 객체 검증이 필요합니다. 입력한 원문(대소문자·줄바꿈·주석)을 그대로 저장합니다.')}</p>
        {sqlChanged && <p className="form-help view-form-note" role="note">{t('정의 SQL 이 변경되었습니다. 출력 컬럼 메타데이터가 현재 SQL 과 일치하는지 확인하세요.')}</p>}
        <div className="index-editor-add">
          <button type="button" className="btn" onClick={() => void showPreview()}>{t('DDL 미리보기')}</button>
        </div>
        {preview && (
          <div className="view-form-preview">
            {preview.error ? <p className="table-ddl-warnings" role="alert">{preview.error}</p>
              : !preview.ddl ? <p className="gh-muted" role="status">{t('DDL 을 생성하고 있습니다.')}</p>
              : (
                <>
                  {(preview.ddl.warnings ?? []).length > 0 && <ul className="table-ddl-warnings">{(preview.ddl.warnings ?? []).map((w, i) => <li key={i}>{w}</li>)}</ul>}
                  <pre className="table-ddl-code"><code>{preview.ddl.ddl}</code></pre>
                  <p className="form-help">{t('{0} 기준 미리보기입니다 (저장·실행하지 않음).', preview.ddl.dbTypeLabel)}</p>
                </>
              )}
          </div>
        )}
      </div>

      <div className="dict-detail-columns index-editor">
        <h4>{t('출력 컬럼 ({0})', columns.length)} <span className="gh-muted">{t('직접 관리하는 정보 · SQL 에서 추출하지 않습니다')}</span></h4>
        <div className="table-wrap">
          <table>
            <thead><tr>{['#', t('컬럼명'), t('논리명'), t('데이터 타입'), t('설명'), t('순서'), ''].map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
            <tbody>
              {columns.length === 0 && <tr><td colSpan={7} className="gh-muted">{t('출력 컬럼이 없습니다.')}</td></tr>}
              {columns.map((c, index) => (
                <tr key={index}>
                  <td className="text-center">{index + 1}</td>
                  <td className="index-editor-main"><input type="text" className="form-control gh-mono" aria-label={t('컬럼명 {0}', index + 1)} maxLength={63} value={c.columnName} onChange={(e) => setColumn(index, { columnName: e.target.value })} /></td>
                  <td><input type="text" className="form-control" aria-label={t('논리명 {0}', index + 1)} maxLength={255} value={c.columnKorName ?? ''} onChange={(e) => setColumn(index, { columnKorName: e.target.value })} /></td>
                  <td><input type="text" className="form-control gh-mono" aria-label={t('데이터 타입 {0}', index + 1)} maxLength={255} placeholder={t('선택')} value={c.dataType ?? ''} onChange={(e) => setColumn(index, { dataType: e.target.value })} /></td>
                  <td><input type="text" className="form-control" aria-label={t('설명 {0}', index + 1)} maxLength={255} value={c.columnDescription ?? ''} onChange={(e) => setColumn(index, { columnDescription: e.target.value })} /></td>
                  <td className="index-editor-order">
                    <button type="button" className="btn btn-sm" aria-label={t('위로')} disabled={index === 0} onClick={() => setColumns(moved(columns, index, -1))}>↑</button>
                    <button type="button" className="btn btn-sm" aria-label={t('아래로')} disabled={index === columns.length - 1} onClick={() => setColumns(moved(columns, index, 1))}>↓</button>
                  </td>
                  <td><button type="button" className="btn btn-sm" onClick={() => setColumns(columns.filter((_, i) => i !== index))}>{t('빼기')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="index-editor-add">
          <button type="button" className="btn" onClick={() => setColumns([...columns, emptyColumn()])}>{t('컬럼 추가')}</button>
        </div>
      </div>

      <div className="dict-detail-columns index-editor">
        <h4>{t('참조 객체 ({0})', references.length)} <span className="gh-muted">{t('직접 관리하는 정보 · SQL 을 분석하지 않으며 SQL 에서 실제로 쓰이는지 보장하지 않습니다')}</span></h4>
        <div className="table-wrap">
          <table>
            <thead><tr>{['#', t('유형'), t('객체'), t('연결'), ''].map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
            <tbody>
              {references.length === 0 && <tr><td colSpan={5} className="gh-muted">{t('참조 객체가 없습니다.')}</td></tr>}
              {references.map((r, index) => (
                <tr key={refKey(r)}>
                  <td className="text-center">{index + 1}</td>
                  <td>{referenceTypeLabel(r.referenceType)}</td>
                  <td className="index-editor-main gh-mono">{referenceName(r)}</td>
                  <td>{isInternal(r) ? <Label variant="success">{t('모델 객체')}</Label> : <Label variant="attention">{t('미확인')}</Label>}</td>
                  <td><button type="button" className="btn btn-sm" onClick={() => setReferences(references.filter((_, i) => i !== index))}>{t('빼기')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="index-editor-add">
          <input type="search" className="form-control" aria-label={t('테이블·VIEW 검색')} placeholder={t('테이블·VIEW 검색')} value={targetQuery} onChange={(e) => setTargetQuery(e.target.value)} />
          <select className="form-control" aria-label={t('참조 객체 추가')} value="" disabled={!available.length} onChange={(e) => addTarget(e.target.value)}>
            <option value="">{available.length ? t('테이블·VIEW 를 골라 추가') : t('추가할 객체가 없습니다')}</option>
            {available.map((target) => (
              <option key={`${target.referenceType}:${target.id}`} value={`${target.referenceType}:${target.id}`}>
                [{referenceTypeLabel(target.referenceType)}] {target.schemaName ? `${target.schemaName}.` : ''}{target.name}{target.korName ? ` (${target.korName})` : ''}
              </option>
            ))}
          </select>
        </div>
        <details className="view-form-external">
          <summary>{t('모델에 없는 객체 (미확인 참조) 추가')}</summary>
          <div className="index-editor-add">
            <select className="form-control" aria-label={t('유형')} value={external.referenceType} onChange={(e) => setExternal({ ...external, referenceType: e.target.value as ViewReference['referenceType'] })}>
              <option value="TABLE">{t('테이블')}</option>
              <option value="VIEW">VIEW</option>
            </select>
            <input type="text" className="form-control gh-mono" aria-label={t('스키마명 (선택)')} placeholder={t('스키마명 (선택)')} maxLength={255} value={external.schemaName} onChange={(e) => setExternal({ ...external, schemaName: e.target.value })} />
            <input type="text" className="form-control gh-mono" aria-label={t('객체명')} placeholder={t('객체명')} maxLength={255} value={external.objectName} onChange={(e) => setExternal({ ...external, objectName: e.target.value })} />
            <button type="button" className="btn" onClick={addExternal}>{t('추가')}</button>
          </div>
          <p className="form-help">{t('이름만 기록하며 실제로 있는 객체인지 확인하지 않습니다. DDL 생성 순서·ERD 에는 쓰지 않습니다.')}</p>
        </details>
      </div>

      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? t('저장 중…') : submitLabel}</button>
      </div>
    </form>
  );
}
