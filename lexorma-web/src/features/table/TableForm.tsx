import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import type { Row } from '../../components/grid/Grid';
import { api, enc } from '../../lib/api';
import { useConflictRecovery, useSubmitting } from '../../components/conflict/ConflictRecovery';
import { blockNonNumericKeys } from '../../lib/format';
import type { Schema } from '../schema/schemas';
import { t } from '../../lib/i18n';
import { BIZ_DESCRIPTION_MAX, BizLength } from '../dictionary/ai/BizDescriptionInfo';

interface TableNameResult {
  valid: boolean;
  tableName: string;
  tableKorName: string;
  tablePrefix?: string;
  words: { word: string; status: 'APPROVED' | 'NOT_APPROVED' | 'NOT_FOUND'; abbreviation?: string; englishName?: string }[];
}

const WORD_STATUS = {
  APPROVED: { variant: 'success', text: '' },
  NOT_APPROVED: { variant: 'attention', text: t('미승인') },
  NOT_FOUND: { variant: 'danger', text: t('미등록') },
} as const;

/** 한글명 비교용 정규화 : "사용_내역_관리" / " 사용 내역  관리 " → "사용 내역 관리" */
function normalizeKorName(value: unknown) {
  return String(value ?? '').trim().split(/[_\s]+/).filter(Boolean).join(' ');
}

/**
 * 테이블 등록(C) / 수정(U)
 * 한글명을 입력하면 표준 단어를 조회해 프로젝트 접두어 + 단어 영문약어로 테이블명을 만든다.
 */
export function TableForm({ type, row = {}, schemas, onSaved }: {
  type: 'C' | 'U';
  row?: Row;
  schemas: Schema[];
  /** 저장한 테이블명 (등록은 표준 단어로 만든 이름 — 물리 ERD 가 새 테이블을 찾는 데 쓴다) */
  onSaved: (saved: { tableName: string; schemaId?: number | null }) => void;
}) {
  const dialog = useDialog();
  const toast = useToast();
  const [busy, run] = useSubmitting();
  type Values = { korName: string; tableName: string; description: string; biz: string; isMaster: boolean; sortOrder: string; schemaId: string };
  const valuesOf = (r: Row): Values => ({
    korName: r.tableKorName ?? '', tableName: r.tableName ?? '', description: r.tableDescription ?? '', biz: String((r as { bizDescription?: string | null }).bizDescription ?? ''),
    isMaster: r.isMasterTable === 'Y', sortOrder: String(r.sortOrder ?? ''), schemaId: r.schemaId != null ? String(r.schemaId) : '',
  });
  // 409 복구 (M8-C3, 수정만) : 테이블 상세의 최신 값과 비교. 다시 적용하면 "원래 값"(이름 유지·업무 설명 기준값·스키마 이동 판단)도 최신 값이 된다
  const recovery = useConflictRecovery<Values>({
    initial: valuesOf(row),
    base: (row as { modifiedAt?: string | null }).modifiedAt,
    fields: [
      { key: 'korName', label: t('테이블 코멘트'), get: (v) => normalizeKorName(v.korName) },
      { key: 'description', label: t('테이블 설명'), get: (v) => v.description },
      { key: 'biz', label: t('업무 설명'), get: (v) => v.biz },
      { key: 'isMaster', label: t('마스터 테이블 여부'), get: (v) => v.isMaster, show: (v) => (v.isMaster ? 'Y' : 'N') },
      { key: 'sortOrder', label: t('정렬 순서'), get: (v) => v.sortOrder },
      { key: 'schemaId', label: t('스키마'), get: (v) => v.schemaId, show: (v) => schemas.find((x) => String(x.id) === v.schemaId)?.schemaName ?? v.schemaId },
    ],
    load: async () => {
      const latest = await api<Row & { modifiedAt?: string | null }>(`/api/selectTable/detail/${enc(row.id)}`, { silent: true });
      return { values: valuesOf(latest), base: latest.modifiedAt ?? null };
    },
  });
  const originalKorName = normalizeKorName(recovery.start.korName);
  const originalTableName = recovery.start.tableName;

  const [korName, setKorName] = useState(row.tableKorName ?? '');
  const [tableName, setTableName] = useState(originalTableName);
  const [description, setDescription] = useState(row.tableDescription ?? '');
  // 업무 설명 (AI-5) : 담당자가 확정한 설명. DDL COMMENT(테이블 설명)·원본 COMMENT 와 따로 둔다
  const originalBizDescription = recovery.start.biz;
  const [bizDescription, setBizDescription] = useState(originalBizDescription);
  const [isMaster, setIsMaster] = useState(row.isMasterTable === 'Y');
  const [sortOrder, setSortOrder] = useState(row.sortOrder ?? '');
  // 등록 : 스키마를 고른다 (고르지 않으면 기본 스키마). 수정 : 미승인 테이블은 다른 스키마로 옮길 수 있다 (DEC-11 — 승인된 테이블은 수정 자체가 막혀 승인 취소 뒤 옮긴다).
  // 바꿨을 때만 schemaId 를 보낸다 (안 보내면 서버가 지금 스키마를 유지한다). 목록은 화면에서 받는다 (대화상자는 AuthProvider 밖에 그려져 useSchemas 를 쓸 수 없다)
  const originalSchemaId = type === 'U' ? recovery.start.schemaId : '';
  const [schemaId, setSchemaId] = useState(originalSchemaId);
  const selectedSchemaId = schemaId || String(schemas.find((s) => s.isDefault)?.id ?? '');
  const schemaMoved = type === 'U' && !!originalSchemaId && schemaId !== originalSchemaId;
  const [state, setState] = useState<{ korName: string; result: TableNameResult | null; pending: boolean }>({ korName: '', result: null, pending: false });
  const seq = useRef(0);
  const autoDescription = useRef('');
  const firstRun = useRef(true);

  // (수정 시) 저장된 한글명과 같으면 현재 테이블명을 유지한다.
  const isUnchanged = (value: string) => originalKorName !== '' && normalizeKorName(value) === originalKorName;

  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false;
      return;
    }
    const value = korName.trim();
    const mySeq = ++seq.current;
    if (!value || isUnchanged(value)) {
      setState({ korName: '', result: null, pending: false });
      setTableName(type === 'C' ? '' : originalTableName);
      return;
    }
    setState((s) => ({ ...s, pending: true }));
    const timer = setTimeout(async () => {
      try {
        const result = await api<TableNameResult>(`/api/selectTable/tableName?tableKorName=${enc(value)}`);
        if (mySeq !== seq.current) return; // 이전 입력에 대한 응답은 무시
        setState({ korName: value, result, pending: false });
        setTableName(result.valid ? result.tableName : type === 'C' ? '' : originalTableName);
        // 설명이 비어있거나 이전에 자동 입력한 값이면 한글명(공백 구분)으로 채운다.
        setDescription((current: string) => {
          if (result.valid && (!current.trim() || current.trim() === autoDescription.current)) {
            autoDescription.current = result.tableKorName;
            return result.tableKorName;
          }
          return current;
        });
      } catch {
        if (mySeq === seq.current) setState((s) => ({ ...s, pending: false }));
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [korName]);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const inputKorName = korName.trim();
    // 수정 시 저장된 한글명을 그대로 두면 서버에 보내지 않는다 (기존 테이블명/한글명 유지)
    const sendKorName = type === 'U' && isUnchanged(inputKorName) ? '' : inputKorName;

    if (type === 'C' && !sendKorName) return dialog.alert(t('테이블 코멘트은(는) 필수 입력 항목입니다.'));
    if (sendKorName) {
      if (state.pending || state.korName !== sendKorName) return dialog.alert(t('표준 단어를 조회하고 있습니다. 잠시 후 다시 시도해주세요.'));
      if (!state.result?.valid) return dialog.alert(t('테이블 코멘트에 승인된 표준 단어가 아닌 단어가 있습니다.'));
    }
    // 한글명 없이 수정하는 경우에는 설명을 직접 입력해야 한다. (한글명이 있으면 서버에서 채운다)
    if (!sendKorName && !String(description).trim()) return dialog.alert(t('테이블 설명은(는) 필수 입력 항목입니다.'));

    const body = {
      id: row.id ?? 0,
      tableKorName: sendKorName,
      tableName,
      tableDescription: description,
      // 수정 : 고쳤을 때만 보낸다 (안 보내면 서버가 그대로 둔다). 읽은 값을 함께 보내 그 사이 다른 사용자·AI 제안 승인이 바꾼 업무 설명을 덮어쓰지 않는다
      ...(type === 'C' ? { bizDescription } : bizDescription !== originalBizDescription ? { bizDescription, bizDescriptionBase: originalBizDescription } : {}),
      isMasterTable: isMaster ? 'Y' : 'N',
      sortOrder: parseInt(String(sortOrder)) || 0,
      ...((type === 'C' && selectedSchemaId) || schemaMoved ? { schemaId: Number(selectedSchemaId) } : {}),
      // 수정 : 화면이 읽은 수정 일시 (DEC-08 — 그 사이 다른 사용자가 고쳤으면 409, 입력은 그대로 남는다)
      ...(type === 'U' ? { baseModifiedAt: recovery.base } : {}),
    };

    const targetSchemaName = schemas.find((s) => String(s.id) === selectedSchemaId)?.schemaName ?? '';
    dialog.confirm(type === 'C' ? t('저장하시겠습니까?') : schemaMoved ? t('스키마를 {0}(으)로 옮겨 수정하시겠습니까?', targetSchemaName) : t('수정하시겠습니까?'), () => void run(async () => {
      let response: { result: boolean };
      try {
        response = await api<{ result: boolean }>('/api/saveTable', { method: type === 'C' ? 'POST' : 'PUT', body, conflict: 'handled' });
      } catch (error) {
        if (type === 'U' && recovery.catchConflict(error, { korName, tableName, description: String(description), biz: bizDescription, isMaster, sortOrder: String(sortOrder), schemaId })) return;
        throw error;
      }
      if (!response.result) return;
      onSaved({ tableName, schemaId: (type === 'C' && selectedSchemaId) || schemaMoved ? Number(selectedSchemaId) : null });
      toast.success(type === 'C' ? t('정상적으로 저장되었습니다.') : t('정상적으로 수정되었습니다.'));
    }));
  };

  const result = state.result;
  return (
    <form id="editTableForm" className="edit-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('korName')) setKorName(latest.korName);
        if (!picks.has('description')) setDescription(latest.description);
        if (!picks.has('biz')) setBizDescription(latest.biz);
        if (!picks.has('isMaster')) setIsMaster(latest.isMaster);
        if (!picks.has('sortOrder')) setSortOrder(latest.sortOrder);
        if (!picks.has('schemaId')) setSchemaId(latest.schemaId);
      })}
      <div className="form-group">
        <label htmlFor="tableSchema">{t('스키마')}</label>
        {type === 'C' || originalSchemaId ? (
          <select className="form-control" id="tableSchema" value={selectedSchemaId} onChange={(e) => setSchemaId(e.target.value)}>
            {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}{s.isDefault ? t(' (기본)') : ''}</option>)}
          </select>
        ) : (
          <input type="text" className="form-control gh-mono" id="tableSchema" readOnly value={row.schemaName ?? ''} />
        )}
        {schemaMoved && (
          <p className="gh-muted" role="note">
            {t('다른 스키마로 옮깁니다. 이 테이블을 참조하던 컬럼의 참조 테이블명은 새 스키마 기준으로 함께 고쳐지고, DDL·ERD 에서는 새 스키마에 나타납니다. 옮길 스키마에 같은 이름의 테이블·VIEW 가 있으면 저장되지 않습니다.')}
          </p>
        )}
      </div>

      <div className="form-group">
        <label htmlFor="tableKorName">{t('테이블 코멘트')}</label>
        <input type="text" className="form-control" id="tableKorName" placeholder={t('예) 사용_내역_관리')} autoComplete="off" required={type === 'C'} autoFocus
          value={korName} onChange={(e) => setKorName(e.target.value)} />
        <p className="table-name-help">
          {type === 'C'
            ? t('표준 단어를 _ 또는 공백으로 구분해 입력하면 프로젝트 접두어 + 단어의 영문약어로 테이블명을 만듭니다.')
            : t('한글명을 바꾸면 표준 단어 기준으로 테이블명을 다시 만듭니다. 그대로 두면 현재 테이블명을 유지합니다.')}
        </p>
        <div id="tableNameWords" className="table-name-words" aria-live="polite">
          {result && result.tablePrefix && result.words.length > 0 && (
            <span className="gh-label" title={t('프로젝트 설정의 테이블명 접두어')}>
              <span>{t('접두어')}</span>
              <span className="table-name-abbr">{result.tablePrefix}</span>
            </span>
          )}
          {result?.words.map((word, i) => {
            const status = WORD_STATUS[word.status] ?? WORD_STATUS.NOT_FOUND;
            return (
              <span key={i} className={`gh-label gh-label-${status.variant}`} title={word.englishName || undefined}>
                <span>{word.word}</span>
                <span className="table-name-abbr">{word.status === 'APPROVED' ? word.abbreviation : status.text}</span>
              </span>
            );
          })}
          {result && !result.valid && result.words.length > 0 && (
            <p className="table-name-error">{t('미등록·미승인 단어는 데이터 사전에서 표준 단어로 등록·승인한 뒤 사용할 수 있습니다.')}</p>
          )}
        </div>
      </div>

      <div className="form-group">
        <label htmlFor="tableName">{t('테이블명')}</label>
        <input type="text" className="form-control gh-mono" id="tableName" placeholder={t('한글명을 입력하면 자동으로 생성됩니다.')} readOnly value={tableName} />
      </div>

      <div className="form-group">
        <label htmlFor="tableDescription">{t('테이블 설명')}</label>
        <input type="text" className="form-control" id="tableDescription" placeholder={t('비워두면 한글명으로 채웁니다.')} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>

      <div className="form-group">
        <label htmlFor="bizDescription">{t('업무 설명')}</label>
        <textarea className="form-control" id="bizDescription" rows={3} maxLength={BIZ_DESCRIPTION_MAX} placeholder={t('테이블의 업무적 의미 (DDL COMMENT 로 나가지 않습니다)')}
                  value={bizDescription} onChange={(e) => setBizDescription(e.target.value)} />
        <BizLength value={bizDescription} />
      </div>

      <div className="form-check" style={{ margin: '10px 0' }}>
        <input type="checkbox" className="form-check-input" id="isMasterTable" checked={isMaster} onChange={(e) => setIsMaster(e.target.checked)} />
        <label className="form-check-label" htmlFor="isMasterTable">{t('마스터 테이블 여부')}</label>
      </div>

      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="sortOrder">{t('정렬 순서')}</label>
        <input type="number" className="form-control" id="sortOrder" value={sortOrder} onKeyDown={blockNonNumericKeys} onChange={(e) => setSortOrder(e.target.value)} />
      </div>

      <button type="submit" className="btn btn-primary" id="btn-save-table" disabled={busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>
    </form>
  );
}
