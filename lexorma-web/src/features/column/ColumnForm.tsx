import { useRef, useState, type FormEvent } from 'react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { api, enc } from '../../lib/api';
import { TermCreateForm, type SavedTerm } from '../dictionary/TermCreateForm';
import { RefTableLookup, refName } from './RefTableLookup';
import { searchTerms, type Term, type TermSearchType } from './terms';
import { t } from '../../lib/i18n';

const DATA_TYPES = ['VARCHAR', 'CHAR', 'NUMBER', 'DATE', 'TIMESTAMP', 'BOOLEAN', 'CLOB', 'BLOB'];

const EXTRA_FLAGS = [
  { id: 'isMasterData', label: t('마스터 데이터 여부') },
  { id: 'isRequired', label: t('필수 입력 여부') },
  { id: 'isSensitive', label: t('민감정보 여부') },
  { id: 'isUnique', label: t('고유값 여부') },
  { id: 'isIndex', label: t('인덱스 생성 여부') },
  { id: 'isEncrypted', label: t('암호화 필요 여부') },
] as const;

interface TableInfo {
  id: number;
  tableName: string;
  tableKorName?: string;
  schemaName?: string | null;
}

type Message = { cls: string; text: string } | null;

/**
 * 컬럼 등록 : 승인된 테이블을 고르고, 컬럼명은 표준용어에서 고른다. (없으면 새 표준용어를 바로 등록)
 */
export function ColumnForm({ onSaved }: { onSaved: () => void }) {
  const dialog = useDialog();

  // 테이블 조회
  const [tableKeyword, setTableKeyword] = useState('');
  const [tables, setTables] = useState<TableInfo[]>([]);
  const [table, setTable] = useState<TableInfo | null>(null);
  const [tableMessage, setTableMessage] = useState<Message>(null);
  const [tableColumns, setTableColumns] = useState<string | null>(null);

  // 표준용어 조회
  const [termType, setTermType] = useState<TermSearchType>('termName');
  const [termKeyword, setTermKeyword] = useState('');
  const [terms, setTerms] = useState<Term[]>([]);
  const [term, setTerm] = useState<Term | null>(null);
  const [termMessage, setTermMessage] = useState<Message>(null);

  // 컬럼명 중복
  const [duplicate, setDuplicate] = useState<{ duplicated: boolean; message: Message; ok?: string }>({ duplicated: false, message: null });

  const [fields, setFields] = useState<Record<string, string>>({ dataType: '', columnDesc: '', maxLength: '', precision: '', scale: '', defaultValue: '', sortOrder: '', refTableName: '', example: '' });
  const [flags, setFlags] = useState<Record<string, boolean>>({});
  const [showExtra, setShowExtra] = useState(false);

  // 비동기 조회 응답에서 쓰는 현재 선택 값 (state 는 렌더 시점 값이라 늦게 도착한 응답에서는 지난 값일 수 있다)
  const tableRef = useRef<TableInfo | null>(null);
  const termRef = useRef<Term | null>(null);
  const pending = useRef({ table: false, term: false, dup: false });
  const seqs = useRef({ table: 0, term: 0, dup: 0 });
  const timers = useRef<{ table?: ReturnType<typeof setTimeout>; term?: ReturnType<typeof setTimeout> }>({});

  const setField = (key: string, value: string) => setFields((f) => ({ ...f, [key]: value }));

  // 선택한 테이블에 같은 컬럼명이 있는지 확인한다. (테이블과 표준용어를 모두 골랐을 때)
  const refreshDuplicate = async (target: TableInfo | null, columnName: string) => {
    const seq = ++seqs.current.dup;
    pending.current.dup = false;
    setDuplicate({ duplicated: false, message: null });
    if (!columnName) return;
    if (!target) {
      setDuplicate({ duplicated: false, message: { cls: 'form-help', text: t('테이블을 선택하면 컬럼명 중복을 확인합니다.') } });
      return;
    }
    pending.current.dup = true;
    try {
      const response = await api<{ duplicated: boolean }>(`/api/selectColumn/duplicate?tableInfoId=${enc(target.id)}&columnName=${enc(columnName)}`);
      if (seq !== seqs.current.dup) return;
      if (response.duplicated === true) {
        setDuplicate({ duplicated: true, message: { cls: 'table-name-error', text: t('{0} 테이블에 이미 같은 컬럼명({1})이 등록되어 있습니다.', target.tableName, columnName) } });
      } else {
        setDuplicate({ duplicated: false, message: null, ok: t('{0} 에 등록 가능 · 중복 없음', target.tableName) });
      }
    } finally {
      if (seq === seqs.current.dup) pending.current.dup = false;
    }
  };

  // 테이블 선택 : 테이블에 이미 등록된 컬럼을 함께 보여준다.
  const selectTable = async (target: TableInfo | null) => {
    tableRef.current = target;
    setTable(target);
    setTableColumns(null);
    setTableMessage(null);
    refreshDuplicate(target, termRef.current?.commonStandardTermAbbreviation ?? '');
    if (!target) return;
    const response = await api<{ columnInfos?: { columnName: string }[] }>(`/api/selectColumn/tableColumn/${enc(target.id)}`);
    if (tableRef.current?.id !== target.id) return; // 그 사이 다른 테이블 선택
    const columns = response.columnInfos || [];
    setTableColumns(columns.length === 0 ? t('등록된 컬럼이 없습니다.') : t('등록된 컬럼 {0}개 : {1}', columns.length, columns.map((c) => c.columnName).join(', ')));
  };

  const onTableKeyword = (value: string) => {
    setTableKeyword(value);
    clearTimeout(timers.current.table);
    tableRef.current = null;
    setTable(null);
    setTableColumns(null);
    setTableMessage(null);
    refreshDuplicate(null, termRef.current?.commonStandardTermAbbreviation ?? '');
    const keyword = value.trim();
    if (!keyword) {
      setTables([]);
      pending.current.table = false;
      return;
    }
    pending.current.table = true;
    timers.current.table = setTimeout(async () => {
      const seq = ++seqs.current.table;
      try {
        const response = await api<{ tableInfos?: TableInfo[] }>(`/api/selectColumn/table/${enc(keyword)}`);
        if (seq !== seqs.current.table) return;
        const list = response.tableInfos || [];
        setTables(list);
        if (list.length === 0) {
          setTableMessage({ cls: 'table-name-error', text: t('조건에 맞는 승인된 테이블이 없습니다.') });
          return;
        }
        // 입력과 정확히 같은 테이블이 있거나 결과가 하나뿐이면 바로 선택
        // 같은 이름이 여러 스키마에 있으면 자동으로 고르지 않는다 (테이블명은 스키마 안에서만 하나다)
        const exact = list.filter((target) => target.tableName === keyword.toUpperCase());
        const auto = exact.length === 1 ? exact[0] : list.length === 1 ? list[0] : null;
        if (auto) selectTable(auto);
        else setTableMessage({ cls: 'form-help', text: t('승인된 테이블 {0}건 · 목록에서 선택하세요.', list.length) });
      } finally {
        if (seq === seqs.current.table) pending.current.table = false;
      }
    }, 300);
  };

  // 표준용어 선택 : 컬럼명/설명/데이터 타입/최대 길이를 채우고 컬럼명 중복을 확인한다.
  const selectTerm = (selected: Term | null) => {
    termRef.current = selected;
    setTerm(selected);
    setTermMessage(null);
    if (!selected) {
      refreshDuplicate(tableRef.current, '');
      return;
    }
    // 데이터 타입은 "VARCHAR(100)" 형태 : 타입과 길이를 나눠 넣는다.
    const match = String(selected.dataType ?? '').match(/^\s*([^(]+?)\s*(?:\((\d+)\))?\s*$/);
    setFields((f) => ({
      ...f,
      columnDesc: String(selected.commonStandardTermName ?? '').replaceAll('_', ' '), // 설명은 읽기 쉽게 공백으로
      dataType: match ? match[1] : (selected.dataType ?? ''),
      maxLength: match?.[2] ? match[2] : f.maxLength,
    }));
    if (selected.isApprovalYn !== 'Y') setTermMessage({ cls: 'table-name-warning', text: t('미승인 표준용어입니다. 등록은 가능하지만 용어 승인이 필요합니다.') });
    refreshDuplicate(tableRef.current, selected.commonStandardTermAbbreviation);
  };

  /**
   * @param prefer     결과가 여러 건일 때 바로 선택할 용어 (없으면 1건일 때만 자동 선택)
   * @param onSelected 자동 선택 후 호출
   */
  const lookupTerms = async (type: TermSearchType, keyword: string, prefer?: (terms: Term[]) => Term | undefined, onSelected?: (t: Term) => void) => {
    const seq = ++seqs.current.term;
    try {
      const list = await searchTerms(type, keyword);
      if (seq !== seqs.current.term) return;
      setTerms(list);
      if (list.length === 0) {
        setTermMessage({ cls: 'table-name-error', text: t('조건에 맞는 표준용어가 없습니다. [+ 새 표준용어 등록]으로 바로 등록할 수 있습니다.') });
        return;
      }
      const auto = prefer?.(list) ?? (list.length === 1 ? list[0] : null);
      if (auto) {
        selectTerm(auto);
        onSelected?.(auto);
      } else {
        setTermMessage({ cls: 'form-help', text: t('표준용어 {0}건 · 목록에서 선택하세요.', list.length) });
      }
    } finally {
      if (seq === seqs.current.term) pending.current.term = false;
    }
  };

  const onTermSearch = (type: TermSearchType, value: string) => {
    setTermType(type);
    setTermKeyword(value);
    clearTimeout(timers.current.term);
    selectTerm(null);
    const keyword = value.trim();
    if (!keyword) {
      setTerms([]);
      pending.current.term = false;
      return;
    }
    pending.current.term = true;
    timers.current.term = setTimeout(() => lookupTerms(type, keyword), 300);
  };

  /** 새 표준용어 등록 팝업 : 컬럼 등록 다이얼로그 위에 띄우고, 저장하면 등록한 용어를 컬럼명으로 바로 선택한다. */
  const openTermCreate = () => {
    // 표준용어명으로 검색하던 값이 있으면 그대로 넘긴다.
    const keyword = termType === 'termName' ? termKeyword.trim() : '';
    dialog.popup({
      title: t('새 표준용어 등록'),
      content: ({ close }: DialogControls) => (
        <div className="popup-container term-create-popup">
          <p className="form-help">{t('등록한 용어는 권한에 따라 바로 승인되거나 승인 대기 상태가 됩니다. 저장하면 컬럼명으로 바로 선택됩니다.')}</p>
          <TermCreateForm initialTermName={keyword} submitText={t('등록 후 선택')} onSaved={(saved) => { close(); findAndSelectTerm(saved); }} />
        </div>
      ),
    });
  };

  /** 방금 등록한 용어를 영문약어명으로 다시 조회해 선택한다. (같은 약어가 여러 건이면 용어명이 같은 것) */
  const findAndSelectTerm = (saved: SavedTerm) => {
    const abbreviation = saved.commonStandardTermAbbreviation;
    setTermType('termAbbreviation');
    setTermKeyword(abbreviation);
    selectTerm(null);
    pending.current.term = true;
    lookupTerms(
      'termAbbreviation',
      abbreviation,
      (list) => list.find((t) => t.commonStandardTermAbbreviation === abbreviation && t.commonStandardTermName === saved.commonStandardTermName)
        ?? list.find((t) => t.commonStandardTermAbbreviation === abbreviation),
      (selected) => dialog.alert(selected.isApprovalYn === 'Y'
        ? t('표준용어 {0} 이(가) 승인 상태로 등록되어 컬럼명으로 선택되었습니다.', abbreviation)
        : t('표준용어 {0} 이(가) 승인 대기 상태로 등록되어 컬럼명으로 선택되었습니다. 용어 승인이 필요합니다.', abbreviation)),
    );
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    // 조회가 끝나고 테이블/표준용어를 선택했으며 컬럼명이 중복되지 않아야 한다.
    if (pending.current.table || pending.current.term || pending.current.dup) return dialog.alert(t('조회 중입니다. 잠시 후 다시 시도해주세요.'));
    if (!table) return dialog.alert(t('테이블을 선택해주세요.'));
    if (!term) return dialog.alert(t('컬럼명으로 사용할 표준용어를 선택해주세요.'));
    if (duplicate.duplicated) return dialog.alert(t('선택한 테이블에 이미 같은 컬럼명이 등록되어 있습니다.'));
    if (!fields.dataType.trim()) return dialog.alert(t('데이터 타입을 선택해주세요.'));
    if (!fields.columnDesc.trim()) return dialog.alert(t('컬럼 설명을 입력해주세요.'));

    const num = (v: string) => (v ? Number(v) : null);
    const yn = (key: string) => (flags[key] ? 'Y' : 'N');
    const data = {
      id: 0,
      tableInfoId: table.id,
      termId: term.id,
      tableName: table.tableName,
      columnName: term.commonStandardTermAbbreviation,
      dataType: fields.dataType.trim(),
      columnDesc: fields.columnDesc.trim(),
      maxLength: num(fields.maxLength),
      precision: num(fields.precision),
      scale: num(fields.scale),
      defaultValue: fields.defaultValue.trim(),
      sortOrder: num(fields.sortOrder),
      refTableName: fields.refTableName.trim(),
      example: fields.example.trim(),
      isPk: yn('isPk'),
      isNullable: yn('isNullable'),
      isMasterData: yn('isMasterData'),
      isRequired: yn('isRequired'),
      isSensitive: yn('isSensitive'),
      isUnique: yn('isUnique'),
      isIndex: yn('isIndex'),
      isEncrypted: yn('isEncrypted'),
    };

    dialog.confirm(t('추가하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>('/api/saveColumn', { method: 'POST', body: data });
      if (response.result) dialog.alert(t('정상적으로 추가되었습니다.'), onSaved);
    });
  };

  const check = (id: string, label: string) => (
    <div className="form-check" key={id}>
      <input type="checkbox" className="form-check-input" id={id} checked={!!flags[id]} onChange={(e) => setFlags((f) => ({ ...f, [id]: e.target.checked }))} />
      <label className="form-check-label" htmlFor={id}>{label}</label>
    </div>
  );

  const message = (m: Message) => m && <p className={m.cls}>{m.text}</p>;

  return (
    <form id="editTableForm" className="edit-form" onSubmit={onSubmit}>
      {/* 필수 정보 : 테이블 (승인된 테이블만) */}
      <div className="form-group">
        <label htmlFor="tableSearchInput">{t('테이블')}</label>
        <input type="text" className="form-control" id="tableSearchInput" autoComplete="off" autoFocus placeholder={t('테이블명 또는 일부를 입력하세요 (예: USE_HIST)')}
          value={tableKeyword} onChange={(e) => onTableKeyword(e.target.value)} />
        <select className="form-control lookup-select" id="tableSelect" size={4} hidden={tables.length === 0 || !tableKeyword.trim()} aria-label={t('테이블 선택')}
          value={table ? String(table.id) : ''} onChange={(e) => selectTable(tables.find((t) => String(t.id) === e.target.value) ?? null)}>
          {tables.map((t) => <option key={t.id} value={t.id}>{refName(t, tables)}{t.tableKorName ? ` · ${t.tableKorName}` : ''}</option>)}
        </select>
        <div id="tableLookupResult" className="table-name-words" aria-live="polite">
          {message(tableMessage)}
          {table && (
            <span className="gh-label gh-label-success">
              <span className="table-name-abbr">{table.tableName}</span>
              <span>{t('선택됨')}</span>
            </span>
          )}
          {table && tableColumns && <p className="form-help lookup-columns">{tableColumns}</p>}
        </div>
      </div>

      {/* 필수 정보 : 컬럼명 (표준용어에서 선택, 없으면 바로 등록) */}
      <div className="form-group">
        <div className="lookup-label-row">
          <label htmlFor="termSearchInput">{t('컬럼명 (표준용어)')}</label>
          <button type="button" className="btn btn-sm" id="btn-create-term" onClick={openTermCreate}>{t('+ 새 표준용어 등록')}</button>
        </div>
        <div className="lookup-search-row">
          <select className="form-control" id="termSearchType" aria-label={t('표준용어 검색 기준')} value={termType} onChange={(e) => onTermSearch(e.target.value as TermSearchType, termKeyword)}>
            <option value="termName">{t('표준용어명')}</option>
            <option value="termAbbreviation">{t('영문약어명')}</option>
          </select>
          <input type="text" className="form-control" id="termSearchInput" autoComplete="off" placeholder={t('예: 사용자 이름 / USER_NM')}
            value={termKeyword} onChange={(e) => onTermSearch(termType, e.target.value)} />
        </div>
        <select className="form-control lookup-select" id="termSelect" size={5} hidden={terms.length === 0 || !termKeyword.trim()} aria-label={t('표준용어 선택')}
          value={term ? String(term.id) : ''} onChange={(e) => selectTerm(terms.find((t) => String(t.id) === e.target.value) ?? null)}>
          {terms.map((item) => (
            <option key={item.id} value={item.id}>
              {`${item.commonStandardTermAbbreviation} · ${item.commonStandardTermName} · ${item.dataType ?? '-'}${item.isApprovalYn === 'Y' ? '' : t(' (미승인)')}`}
            </option>
          ))}
        </select>
        <div id="termLookupResult" className="table-name-words" aria-live="polite">{message(termMessage)}</div>
        <input type="text" className="form-control gh-mono" id="columnName" placeholder={t('표준용어를 선택하면 자동으로 입력됩니다.')} readOnly value={term?.commonStandardTermAbbreviation ?? ''} />
        <div id="columnDuplicateResult" className="table-name-words" aria-live="polite">
          {message(duplicate.message)}
          {duplicate.ok && <span className="gh-label gh-label-success">{duplicate.ok}</span>}
        </div>
      </div>

      <div className="form-group">
        <label htmlFor="dataType">{t('데이터 타입')}</label>
        <input type="text" className="form-control" id="dataType" list="dataTypeOptions" value={fields.dataType} onChange={(e) => setField('dataType', e.target.value)} />
      </div>
      <datalist id="dataTypeOptions">
        {DATA_TYPES.map((dt) => <option key={dt} value={dt} />)}
      </datalist>

      <div className="form-group">
        <label htmlFor="columnDesc">{t('컬럼 설명')}</label>
        <input type="text" className="form-control" id="columnDesc" value={fields.columnDesc} onChange={(e) => setField('columnDesc', e.target.value)} />
      </div>

      {check('isNullable', t('NULL 허용 여부'))}
      {check('isPk', t('PK 여부'))}

      <div className="form-group">
        <label htmlFor="maxLength">{t('최대 길이')}</label>
        <input type="number" className="form-control" id="maxLength" value={fields.maxLength} onChange={(e) => setField('maxLength', e.target.value)} />
      </div>

      {/* 부가 정보 펼치기 */}
      <div className="form-group mt-3">
        <button type="button" className="btn btn-sm" onClick={() => setShowExtra((v) => !v)}>{t('부가 정보 보기 ▼')}</button>
      </div>

      <div id="extra-info-section" style={{ display: showExtra ? 'block' : 'none', marginTop: 12, borderTop: '1px solid #d1d9e0b3', paddingTop: 16 }}>
        {([['precision', t('정밀도'), 'number'], ['scale', t('소수 자릿수'), 'number'], ['defaultValue', t('기본값'), 'text'], ['sortOrder', t('정렬 순서'), 'number']] as const).map(([key, label, type]) => (
          <div className="form-group" key={key}>
            <label htmlFor={key}>{label}</label>
            <input type={type} className="form-control" id={key} value={fields[key]} onChange={(e) => setField(key, e.target.value)} />
          </div>
        ))}

        <div className="form-group">
          <label htmlFor="refTableName">{t('참조 테이블명')}</label>
          <RefTableLookup id="refTableName" value={fields.refTableName} onChange={(v) => setField('refTableName', v)}
            placeholder={t('테이블명 일부를 입력하면 승인된 테이블 목록이 나옵니다 (예: TB_USER)')} />
        </div>

        <div className="form-group">
          <label htmlFor="example">{t('데이터 예시')}</label>
          <input type="text" className="form-control" id="example" value={fields.example} onChange={(e) => setField('example', e.target.value)} />
        </div>

        {EXTRA_FLAGS.map((f) => check(f.id, f.label))}
      </div>

      <button type="submit" className="btn btn-primary" id="btn-save-column">{t('저장')}</button>
    </form>
  );
}
