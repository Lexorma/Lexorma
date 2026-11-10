import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { ChevronDownIcon, ChevronUpIcon } from '@primer/octicons-react';
import type { Row } from '../../components/grid/Grid';
import { api, ApiError, enc } from '../../lib/api';
import { blockNonNumericKeys } from '../../lib/format';
import { TermCreateForm, type SavedTerm } from '../dictionary/TermCreateForm';
import { CommonColumnSet, type CommonColumnItem } from './CommonColumnSet';
import { RefTableLookup } from './RefTableLookup';
import { applyRecommendation } from './recommend';
import { searchTerms, termLabel, type Term, type TermSearchType } from './terms';
import { t, tc } from '../../lib/i18n';

/**
 * 컬럼 일괄 편집 (테이블 관리 > 테이블 상세 > 컬럼 편집)
 *
 * - 표준용어를 검색해 여러 개를 체크한 뒤 한 번에 추가하고, 표 안에서 설명/타입/길이/PK/NULL/기본값 등을 바로 고친다.
 * - 승인 전 컬럼끼리 순서를 바꾸고, 승인 전 컬럼을 삭제(되돌리기 가능)한다. 승인된 컬럼은 잠근다.
 * - [일괄 저장] 은 PUT /api/saveColumn/bulk 한 번으로 보낸다. 서버 검증 오류가 있으면 아무것도 저장되지 않고 행별로 오류를 보여준다.
 */

const RECOMMEND_STORAGE_KEY = 'metamora.columnBulk.recommend';

function loadRecommendSetting() {
  try {
    return localStorage.getItem(RECOMMEND_STORAGE_KEY) !== 'N';
  } catch {
    return true;
  }
}

function saveRecommendSetting(on: boolean) {
  try {
    localStorage.setItem(RECOMMEND_STORAGE_KEY, on ? 'Y' : 'N');
  } catch {
    // 저장소를 쓸 수 없으면 이번 화면에서만 유지한다
  }
}

const DATA_TYPES = ['VARCHAR', 'CHAR', 'NUMBER', 'INTEGER', 'BIGINT', 'DATE', 'TIMESTAMP', 'BOOLEAN', 'CLOB', 'BLOB'];

// 행 펼치기(상세)에서 고치는 Y/N 항목
const DETAIL_FLAGS: [string, string][] = [
  ['isRequired', t('필수 입력')],
  ['isUnique', t('고유값')],
  ['isIndex', t('인덱스')],
  ['isMasterData', t('마스터 데이터')],
  ['isSensitive', t('민감정보')],
  ['isEncrypted', t('암호화')],
];

// 변경 여부 비교 / 저장에 쓰는 항목
const FIELD_KEYS = ['columnDesc', 'dataType', 'maxLength', 'precision', 'scale', 'defaultValue', 'refTableName', 'example',
  'isPk', 'isNullable', 'isRequired', 'isUnique', 'isIndex', 'isMasterData', 'isSensitive', 'isEncrypted'];

const NUMBER_KEYS = ['maxLength', 'precision', 'scale'];

type Fields = Record<string, string | null>;

interface BulkRow {
  key: string;
  id: number | null;
  termId: number | null;
  columnName: string;
  approved: boolean;
  isNew: boolean;
  termNotApproved: boolean;
  fields: Fields;
  original: Fields | null;
  deleted?: boolean;
  expanded?: boolean;
  error?: string | null;
  recommended?: string[];
  source?: { label: string; title: string };
}

interface ColumnInfo extends Row {
  id: number;
  columnName: string;
  sortOrder?: number | null;
  standardTerms?: { id: number; isApprovalYn: string } | null;
}

// ---------------------------------------------------------------- 행 데이터 변환

function normalize(key: string, value: unknown): string | null {
  if (key.startsWith('is')) return value === 'Y' ? 'Y' : 'N';
  if (value === null || value === undefined || value === '') return null;
  return String(value);
}

function fromExisting(col: ColumnInfo): BulkRow {
  const fields: Fields = {};
  FIELD_KEYS.forEach((key) => (fields[key] = normalize(key, col[key])));
  return {
    key: String(col.id),
    id: col.id,
    termId: col.standardTerms?.id ?? null,
    columnName: col.columnName,
    approved: col.isApproval === 'Y',
    isNew: false,
    termNotApproved: false,
    fields,
    original: { ...fields },
  };
}

/**
 * 표준용어로 새 컬럼 행을 만든다 : 설명은 용어명(공백 구분), 데이터 타입 "VARCHAR(100)" / "NUMBER(12,2)" 은 타입과 길이/정밀도로 나눈다.
 * NULL 허용은 기본으로 켜 둔다 (NOT NULL 은 PK 또는 직접 선택).
 */
function fromTerm(term: Term, key: string): BulkRow {
  const raw = String(term.dataType ?? '').trim();
  const match = raw.match(/^\s*([^(]+?)\s*(?:\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\))?\s*$/);
  const base = match ? match[1].toUpperCase() : raw.toUpperCase();
  const isNumeric = ['NUMBER', 'NUMERIC', 'DECIMAL'].includes(base);

  const fields: Fields = {};
  FIELD_KEYS.forEach((k) => (fields[k] = k.startsWith('is') ? 'N' : null));
  Object.assign(fields, { columnDesc: termLabel(term), dataType: base || null, isNullable: 'Y' });
  if (match?.[2]) {
    if (isNumeric) {
      fields.precision = match[2];
      if (match[3]) fields.scale = match[3];
    } else {
      fields.maxLength = match[2];
    }
  }

  return { key, id: null, termId: term.id, columnName: term.commonStandardTermAbbreviation, approved: false, isNew: true, termNotApproved: term.isApprovalYn !== 'Y', fields, original: null };
}

/** 공통 컬럼 세트 항목으로 새 행 : 용어로 기본값을 채운 뒤 세트의 NULL 허용/기본값/인덱스를 덮어쓴다. (자동 추천은 쓰지 않는다) */
function fromCommonColumn(item: CommonColumnItem, key: string): BulkRow {
  const row = fromTerm(item.term!, key);
  row.fields.isNullable = item.isNullable === 'Y' ? 'Y' : 'N';
  row.fields.isIndex = item.isIndex === 'Y' ? 'Y' : 'N';
  if (item.defaultValue) row.fields.defaultValue = item.defaultValue;
  row.source = { label: t('공통'), title: t('프로젝트 공통 컬럼 세트에서 추가') };
  return row;
}

/** 다른 테이블의 컬럼을 복사 : 이 테이블에 이미 PK 가 있으면 복사한 PK 는 풀어 둔다. (의도하지 않은 복합 PK 방지) */
function fromCopiedColumn(col: ColumnInfo, key: string, sourceTableName: string, targetHasPk: boolean): BulkRow {
  const fields: Fields = {};
  FIELD_KEYS.forEach((k) => (fields[k] = normalize(k, col[k])));
  const notes = [t('{0} 에서 복사', sourceTableName)];
  if (targetHasPk && fields.isPk === 'Y') {
    fields.isPk = 'N';
    notes.push(t('이 테이블에 이미 PK 가 있어 PK 는 해제했습니다'));
  }
  return {
    key,
    id: null,
    termId: col.standardTerms!.id,
    columnName: col.columnName,
    approved: false,
    isNew: true,
    termNotApproved: col.standardTerms!.isApprovalYn !== 'Y',
    fields,
    original: null,
    source: { label: tc('출처', '복사'), title: notes.join(' · ') },
  };
}

function toPayloadRow(row: BulkRow) {
  const payload: Record<string, unknown> = { key: row.key, id: row.id, termId: row.isNew ? row.termId : null };
  FIELD_KEYS.forEach((key) => {
    const value = row.fields[key];
    if (NUMBER_KEYS.includes(key)) payload[key] = value === null || value === '' ? null : Number(value);
    else if (key.startsWith('is')) payload[key] = value === 'Y' ? 'Y' : 'N';
    else payload[key] = value === null ? null : String(value);
  });
  return payload;
}

function isModified(row: BulkRow) {
  if (!row.original) return false;
  return FIELD_KEYS.some((key) => String(row.fields[key] ?? '').trim() !== String(row.original![key] ?? '').trim());
}

/**
 * 붙여넣은 글을 줄 단위로 나눈다. 엑셀에서 여러 칸을 복사하면 첫 칸만, 한 줄에 쉼표로 나열했으면 쉼표로 나눈다.
 * 앞의 번호/글머리표(1. 2) - * •)는 지운다.
 */
function parsePaste(text: string) {
  let lines = String(text ?? '').split(/\r?\n/).map((line) => line.split('\t')[0]);
  const nonEmpty = lines.filter((line) => line.trim());
  if (nonEmpty.length === 1 && nonEmpty[0].includes(',')) lines = nonEmpty[0].split(',');
  return lines.map((line) => line.replace(/^\s*(?:\d+\s*[.)]|[-*•·])\s*/, '').trim()).filter(Boolean);
}

interface ResolvedItem {
  input: string;
  status: 'MATCHED' | 'AMBIGUOUS' | 'NOT_FOUND';
  terms?: Term[];
}

interface PasteItem {
  input: string;
  status: ResolvedItem['status'];
  terms: Term[];
  selectedId: number | null;
  checked: boolean;
}

function toPasteItem(item: ResolvedItem): PasteItem {
  return {
    input: item.input,
    status: item.status,
    terms: item.terms || [],
    selectedId: item.terms?.[0]?.id ?? null, // 여러 건이면 승인된 용어가 앞에 온다
    checked: item.status !== 'NOT_FOUND',
  };
}

const selectedTerm = (item: PasteItem) => item.terms.find((t) => t.id === item.selectedId) ?? null;

async function resolveInputs(inputs: string[]): Promise<ResolvedItem[]> {
  const response = await api<{ items?: ResolvedItem[] }>('/api/selectColumn/term/resolve', { method: 'POST', body: inputs });
  return response.items || [];
}

interface TableRef {
  id: number;
  tableName: string;
  tableKorName?: string;
}

/** 테이블 상세에서 연다 : 컬럼을 조회한 뒤 넓은 다이얼로그(dialog-xl)로 띄운다 */
export function useColumnBulkEditor() {
  const dialog = useDialog();
  return async (table: Row, { onSaved }: { onSaved?: () => void } = {}) => {
    let columnInfos: ColumnInfo[];
    try {
      const response = await api<{ columnInfos?: ColumnInfo[] }>(`/api/selectColumn/tableColumn/${enc(table.id)}`, { silent: true });
      columnInfos = response.columnInfos || [];
    } catch (e) {
      dialog.alert(e instanceof ApiError && e.status === 403 ? t('컬럼을 편집할 권한이 없습니다.') : e instanceof ApiError ? e.message : t('컬럼을 불러오지 못했습니다.'));
      return;
    }
    dialog.open({
      title: t('컬럼 편집 · {0}', `${table.tableName ?? ''}${table.tableKorName ? ` (${table.tableKorName})` : ''}`),
      className: 'dialog-xl',
      content: (controls: DialogControls) => <ColumnBulkEditor table={table as TableRef} columnInfos={columnInfos} controls={controls} onSaved={onSaved} />,
    });
  };
}

function ColumnBulkEditor({ table, columnInfos, controls, onSaved }: { table: TableRef; columnInfos: ColumnInfo[]; controls: DialogControls; onSaved?: () => void }) {
  const dialog = useDialog();
  const seq = useRef(0);
  const nextKey = () => `new-${++seq.current}`;

  const [rows, setRows] = useState<BulkRow[]>(() =>
    columnInfos
      .slice()
      .sort((a, b) => (a.sortOrder ?? Number.MAX_SAFE_INTEGER) - (b.sortOrder ?? Number.MAX_SAFE_INTEGER))
      .map(fromExisting),
  );
  const originalOrder = useMemo(() => rows.map((r) => r.id), []);
  const rowsRef = useRef(rows);
  rowsRef.current = rows;

  const [mode, setMode] = useState<'search' | 'paste' | 'copy'>('search');
  const [recommend, setRecommend] = useState(loadRecommendSetting); // 속성 자동 추천 사용 여부 (브라우저에 기억)
  const [generalErrors, setGeneralErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [commonEditable, setCommonEditable] = useState(false);
  const tableWrapRef = useRef<HTMLDivElement>(null);
  const [scrollTarget, setScrollTarget] = useState<{ key?: string; bottom?: boolean } | null>(null);

  // 검색
  const [searchType, setSearchType] = useState<TermSearchType>('termName');
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<Term[]>([]);
  const [checkedIds, setCheckedIds] = useState<Set<number>>(new Set());
  const searchSeq = useRef(0);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // 붙여넣기
  const [pasteText, setPasteText] = useState('');
  const [pasteItems, setPasteItems] = useState<PasteItem[]>([]);
  const [resolving, setResolving] = useState(false);

  // 다른 테이블에서 복사
  const [copyKeyword, setCopyKeyword] = useState('');
  const [copyTables, setCopyTables] = useState<TableRef[]>([]);
  const [copyTable, setCopyTable] = useState<TableRef | null>(null);
  const [copyColumns, setCopyColumns] = useState<ColumnInfo[]>([]);
  const [copyChecked, setCopyChecked] = useState<Set<number>>(new Set());
  const [copyMessage, setCopyMessage] = useState('');
  const copySeq = useRef(0);

  useEffect(() => {
    api<{ editable?: boolean }>('/api/commonColumn').then((response) => setCommonEditable(response.editable === true));
  }, []);

  useEffect(() => {
    searchInputRef.current?.focus();
  }, []);

  // 추가/오류 후 스크롤 (다시 그린 뒤)
  useEffect(() => {
    if (!scrollTarget) return;
    const wrap = tableWrapRef.current;
    if (scrollTarget.bottom && wrap) {
      wrap.scrollTop = wrap.scrollHeight;
      wrap.scrollIntoView({ block: 'nearest' }); // 화면이 낮으면 다이얼로그 안에서 표가 보이도록
    } else if (scrollTarget.key) {
      wrap?.querySelector(`tr[data-key="${CSS.escape(scrollTarget.key)}"]`)?.scrollIntoView({ block: 'center' });
    }
    setScrollTarget(null);
  }, [scrollTarget]);

  const usedColumnNames = useMemo(() => new Set(rows.filter((r) => !r.deleted).map((r) => r.columnName)), [rows]);

  // ---------------------------------------------------------------- 표준용어 검색
  useEffect(() => {
    const value = keyword.trim();
    const mySeq = ++searchSeq.current;
    if (!value) {
      setResults([]);
      return;
    }
    const timer = setTimeout(async () => {
      const terms = await searchTerms(searchType, value);
      if (mySeq === searchSeq.current) setResults(terms);
    }, 300);
    return () => clearTimeout(timer);
  }, [keyword, searchType]);

  // 이미 추가된 용어는 체크 해제
  const selectableIds = results.filter((t) => !usedColumnNames.has(t.commonStandardTermAbbreviation)).map((t) => t.id);
  const checkedCount = selectableIds.filter((id) => checkedIds.has(id)).length;

  /**
   * 새 행을 표 끝에 넣는다. 이미 있는 컬럼명은 건너뛴다.
   * @param items term 이 있으면(용어로 만든 행) 자동 추천을 적용한다. 공통 컬럼/복사 행은 term 없이 넘겨 값 그대로 쓴다.
   * @returns 추가한 행 수
   */
  const pushRows = (items: { row: BulkRow; term?: Term }[]) => {
    const current = rowsRef.current;
    const used = new Set(current.filter((r) => !r.deleted).map((r) => r.columnName));
    const next = current.slice();
    let added = 0;
    items.forEach(({ row, term }) => {
      if (!row.columnName || used.has(row.columnName)) return;
      used.add(row.columnName);
      if (term && recommend) {
        const remaining = next.filter((r) => !r.deleted);
        row.recommended = applyRecommendation(term, row.fields, {
          isFirst: remaining.length === 0,
          hasPk: remaining.some((r) => r.fields.isPk === 'Y'),
        });
      }
      next.push(row);
      added++;
    });
    rowsRef.current = next;
    setRows(next);
    if (added > 0) setScrollTarget({ bottom: true });
    return added;
  };

  const addTerms = (terms: Term[]) => pushRows(terms.map((term) => ({ row: fromTerm(term, nextKey()), term })));

  const addChecked = () => {
    const terms = results.filter((t) => checkedIds.has(t.id) && !usedColumnNames.has(t.commonStandardTermAbbreviation));
    addTerms(terms);
    setCheckedIds(new Set());
  };

  // ---------------------------------------------------------------- 새 표준용어 등록 (팝업 다이얼로그)
  const openTermCreate = (initialName: string, onCreated: (saved: SavedTerm) => void) => {
    dialog.popup({
      title: t('새 표준용어 등록'),
      content: ({ close }: DialogControls) => (
        <div className="popup-container term-create-popup">
          <p className="form-help">{t('등록한 용어는 권한에 따라 바로 승인되거나 승인 대기 상태가 됩니다.')}</p>
          <TermCreateForm initialTermName={initialName} submitText={t('등록')} onSaved={(saved) => { close(); onCreated(saved); }} />
        </div>
      ),
    });
  };

  const createTermAndAdd = () => {
    openTermCreate(searchType === 'termName' ? keyword.trim() : '', async (saved) => {
      // 등록한 용어를 영문약어명으로 다시 찾아 바로 컬럼으로 추가한다
      const abbreviation = saved.commonStandardTermAbbreviation;
      setMode('search');
      setSearchType('termAbbreviation');
      setKeyword(abbreviation);
      const terms = await searchTerms('termAbbreviation', abbreviation);
      const term = terms.find((t) => t.commonStandardTermAbbreviation === abbreviation && t.commonStandardTermName === saved.commonStandardTermName)
        ?? terms.find((t) => t.commonStandardTermAbbreviation === abbreviation);
      if (term && addTerms([term]) > 0) {
        dialog.alert(term.isApprovalYn === 'Y'
          ? t('표준용어 {0} 이(가) 승인 상태로 등록되어 컬럼으로 추가되었습니다.', abbreviation)
          : t('표준용어 {0} 이(가) 승인 대기 상태로 등록되어 컬럼으로 추가되었습니다.', abbreviation));
      }
    });
  };

  // ---------------------------------------------------------------- 공통 컬럼 세트
  const addCommonColumns = async () => {
    const response = await api<{ items?: CommonColumnItem[] }>('/api/commonColumn');
    const items = response.items || [];
    if (items.length === 0) {
      dialog.alert(commonEditable
        ? t('공통 컬럼 세트가 비어 있습니다. [공통 컬럼 세트 관리]에서 먼저 정해주세요.')
        : t('공통 컬럼 세트가 비어 있습니다. 프로젝트 관리자에게 세트 등록을 요청해주세요.'));
      return;
    }
    const missing = items.filter((item) => !item.term).length;
    const used = new Set(rowsRef.current.filter((r) => !r.deleted).map((r) => r.columnName));
    const skipped = items.filter((item) => item.term && used.has(item.term.commonStandardTermAbbreviation)).length;
    const added = pushRows(items.filter((item) => item.term).map((item) => ({ row: fromCommonColumn(item, nextKey()) })));

    const notes = [t('공통 컬럼 {0}개를 추가했습니다.', added)];
    if (skipped) notes.push(t('이미 있는 {0}개는 건너뛰었습니다.', skipped));
    if (missing) notes.push(t('표준용어가 지워진 {0}개는 추가하지 못했습니다.', missing));
    dialog.alert(notes.join(' '));
  };

  const openCommonSet = () => {
    dialog.popup({ title: t('공통 컬럼 세트 관리'), size: 'large', content: (c: DialogControls) => <CommonColumnSet controls={c} /> });
  };

  // ---------------------------------------------------------------- 다른 테이블에서 복사
  useEffect(() => {
    const value = copyKeyword.trim();
    const mySeq = ++copySeq.current;
    if (!value) {
      setCopyTables([]);
      return;
    }
    const timer = setTimeout(async () => {
      const response = await api<{ tableInfos?: TableRef[] }>(`/api/selectColumn/table/${enc(value)}`);
      if (mySeq !== copySeq.current) return;
      // 지금 편집 중인 테이블은 빼고, 승인된 테이블만 (조회 API 기준)
      const list = (response.tableInfos || []).filter((t) => String(t.id) !== String(table.id));
      setCopyTables(list);
      if (list.length === 0) {
        setCopyTable(null);
        setCopyColumns([]);
        setCopyMessage(t('조건에 맞는 다른 승인 테이블이 없습니다.'));
      } else {
        setCopyMessage('');
        if (list.length === 1) selectCopyTable(list[0]);
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [copyKeyword]);

  const copyTableRef = useRef<TableRef | null>(null);
  const selectCopyTable = async (source: TableRef | undefined) => {
    if (!source) return;
    copyTableRef.current = source;
    setCopyTable(source);
    setCopyChecked(new Set());
    const response = await api<{ columnInfos?: ColumnInfo[] }>(`/api/selectColumn/tableColumn/${enc(source.id)}`);
    if (copyTableRef.current !== source) return; // 그 사이 다른 테이블 선택
    setCopyColumns((response.columnInfos || []).slice().sort((a, b) => (a.sortOrder ?? Number.MAX_SAFE_INTEGER) - (b.sortOrder ?? Number.MAX_SAFE_INTEGER)));
  };

  const copyCheckable = copyColumns.filter((col) => col.standardTerms?.id && !usedColumnNames.has(col.columnName)).map((col) => col.id);
  const copyCheckedCount = copyCheckable.filter((id) => copyChecked.has(id)).length;

  const addCopied = () => {
    const columns = copyColumns.filter((col) => copyChecked.has(col.id) && copyCheckable.includes(col.id));
    const hasPk = rowsRef.current.some((r) => !r.deleted && r.fields.isPk === 'Y');
    pushRows(columns.map((col) => ({ row: fromCopiedColumn(col, nextKey(), copyTable!.tableName, hasPk) })));
    setCopyChecked(new Set());
  };

  // ---------------------------------------------------------------- 붙여넣기로 추가
  const findPaste = async () => {
    const inputs = parsePaste(pasteText);
    if (inputs.length === 0) return dialog.alert(t('붙여넣은 내용이 없습니다.'));
    if (inputs.length > 300) return dialog.alert(t('한 번에 300줄까지 찾을 수 있습니다. 나눠서 붙여넣어 주세요.'));
    setResolving(true);
    try {
      setPasteItems((await resolveInputs(inputs)).map(toPasteItem));
    } finally {
      setResolving(false);
    }
  };

  // 붙여넣기 목록 상태 : 이미 추가됨 / 붙여넣기 안에서 같은 컬럼 → 체크 불가
  const pasteView = useMemo(() => {
    const seen = new Set<string>();
    return pasteItems.map((item) => {
      const term = selectedTerm(item);
      const abbr = term?.commonStandardTermAbbreviation;
      const already = !!abbr && usedColumnNames.has(abbr);
      const duplicated = !!abbr && !already && seen.has(abbr);
      if (abbr) seen.add(abbr);
      const blocked = !term || already || duplicated;
      return { item, term, already, duplicated, blocked, checked: item.checked && !blocked };
    });
  }, [pasteItems, usedColumnNames]);
  const pasteCheckedCount = pasteView.filter((v) => v.checked).length;
  const pasteCounts = { MATCHED: 0, AMBIGUOUS: 0, NOT_FOUND: 0 };
  pasteItems.forEach((item) => (pasteCounts[item.status] = (pasteCounts[item.status] ?? 0) + 1));

  const addPasted = () => {
    // 붙여넣은 줄 순서대로 추가한다
    const terms = pasteView.filter((v) => v.checked).map((v) => v.term!).filter(Boolean);
    const added = addTerms(terms);
    // 추가가 끝나면 붙여넣기 영역을 비워 표가 보이게 한다 (못 찾은 줄이 남아 있으면 그대로 둔다)
    if (added > 0 && !pasteItems.some((item) => item.status === 'NOT_FOUND')) {
      setPasteText('');
      setPasteItems([]);
    }
  };

  /** 붙여넣기에서 찾지 못한 줄을 새 표준용어로 등록하고, 그 줄을 다시 찾는다. */
  const createTermForPaste = (index: number) => {
    const item = pasteItems[index];
    openTermCreate(item.input, async (saved) => {
      const items = await resolveInputs([item.input]);
      let next = items[0] ? toPasteItem(items[0]) : null;
      // 입력한 글이 용어명과 다르면(예: 영문약어로 붙여넣음) 등록한 약어로 다시 찾는다
      if (!next || next.status === 'NOT_FOUND') {
        const byAbbr = await resolveInputs([saved.commonStandardTermAbbreviation]);
        const found = byAbbr[0] ? toPasteItem(byAbbr[0]) : null;
        next = found ? { ...found, input: item.input } : null;
      }
      if (next) setPasteItems((list) => list.map((it, i) => (i === index ? next! : it)));
    });
  };

  const updatePasteItem = (index: number, patch: Partial<PasteItem>) => setPasteItems((list) => list.map((it, i) => (i === index ? { ...it, ...patch } : it)));

  // ---------------------------------------------------------------- 컬럼 표
  const updateRow = (key: string, update: (row: BulkRow) => BulkRow) => setRows((list) => {
    const next = list.map((r) => (r.key === key ? update(r) : r));
    rowsRef.current = next;
    return next;
  });

  const setFieldValue = (row: BulkRow, field: string, value: string | null) => updateRow(row.key, (r) => ({ ...r, error: null, fields: { ...r.fields, [field]: value } }));

  const setFlag = (row: BulkRow, field: string, checked: boolean) => updateRow(row.key, (r) => {
    const fields = { ...r.fields, [field]: checked ? 'Y' : 'N' };
    if (field === 'isPk' && checked) fields.isNullable = 'N'; // PK 는 NULL 비허용
    return { ...r, error: null, fields };
  });

  // 승인 전 컬럼끼리만 자리를 바꾼다. 승인된 컬럼은 자리를 지키고 다른 컬럼이 넘어갈 수도 없다. (삭제 예정 행은 건너뛴다)
  const neighbor = (index: number, dir: number) => {
    let j = index + dir;
    while (j >= 0 && j < rows.length && rows[j].deleted) j += dir;
    return j >= 0 && j < rows.length ? j : -1;
  };
  const canMove = (index: number, dir: number) => {
    const row = rows[index];
    if (row.approved || row.deleted) return false;
    const j = neighbor(index, dir);
    return j >= 0 && !rows[j].approved;
  };
  const move = (index: number, dir: number) => {
    const j = neighbor(index, dir);
    if (j < 0) return;
    const next = rows.slice();
    [next[index], next[j]] = [next[j], next[index]];
    rowsRef.current = next;
    setRows(next);
  };

  const removeRow = (index: number) => {
    const row = rows[index];
    const next = row.isNew ? rows.filter((_, i) => i !== index) : rows.map((r, i) => (i === index ? { ...r, deleted: true, error: null } : r));
    rowsRef.current = next;
    setRows(next);
  };

  const restoreRow = (row: BulkRow) => {
    if (usedColumnNames.has(row.columnName)) {
      dialog.alert(t('같은 컬럼명({0})이 이미 추가되어 있어 되돌릴 수 없습니다.', row.columnName));
      return;
    }
    updateRow(row.key, (r) => ({ ...r, deleted: false, error: null }));
  };

  // ---------------------------------------------------------------- 변경 요약 / 저장
  const counts = useMemo(() => {
    const current = rows.filter((r) => !r.deleted && !r.isNew).map((r) => r.id);
    const original = originalOrder.filter((id) => current.includes(id));
    return {
      created: rows.filter((r) => r.isNew).length,
      updated: rows.filter((r) => !r.isNew && !r.deleted && !r.approved && isModified(r)).length,
      deleted: rows.filter((r) => r.deleted).length,
      reordered: current.some((id, i) => id !== original[i]),
    };
  }, [rows, originalOrder]);
  const isDirty = counts.created + counts.updated + counts.deleted > 0 || counts.reordered;

  const onCancel = () => {
    if (isDirty) dialog.confirm(t('저장하지 않은 변경 내용이 있습니다. 닫을까요?'), controls.close);
    else controls.close();
  };

  const onSave = () => {
    // 화면에서 먼저 확인 : 필수값 (서버도 같은 기준으로 다시 확인한다)
    let firstError: string | null = null;
    const checked = rows.map((row) => {
      if (row.deleted || row.approved) return { ...row, error: null };
      const missing: string[] = [];
      if (!String(row.fields.columnDesc ?? '').trim()) missing.push(t('컬럼 설명'));
      if (!String(row.fields.dataType ?? '').trim()) missing.push(t('데이터 타입'));
      if (missing.length) {
        firstError ??= row.key;
        return { ...row, error: t('{0} : {1}을(를) 입력해주세요.', row.columnName, missing.join(', ')) };
      }
      return { ...row, error: null };
    });
    setGeneralErrors([]);
    rowsRef.current = checked;
    setRows(checked);
    if (firstError) {
      setScrollTarget({ key: firstError });
      return;
    }

    const message = t('신규 {0}건, 수정 {1}건, 삭제 {2}건{3}을 저장하시겠습니까?', counts.created, counts.updated, counts.deleted, counts.reordered ? t(', 순서 변경') : '');
    dialog.confirm(message, save);
  };

  const save = async () => {
    const current = rowsRef.current;
    const payload = {
      tableInfoId: table.id,
      rows: current.filter((r) => !r.deleted).map(toPayloadRow),
      deletedIds: current.filter((r) => r.deleted).map((r) => r.id),
    };
    setSaving(true);
    try {
      const response = await api<{ result: boolean; created: number; updated: number; deleted: number; errors?: { key: string; message: string }[] }>(
        '/api/saveColumn/bulk', { method: 'PUT', body: payload });
      if (!response.result) {
        const general: string[] = [];
        const errors = response.errors || [];
        const next = rowsRef.current.map((r) => {
          const err = errors.find((e) => e.key === r.key);
          return err ? { ...r, error: err.message } : r;
        });
        errors.forEach((err) => {
          if (!next.some((r) => r.key === err.key)) general.push(err.message);
        });
        rowsRef.current = next;
        setRows(next);
        setGeneralErrors(general);
        const first = next.find((r) => r.error);
        if (first) setScrollTarget({ key: first.key });
        return;
      }
      controls.close();
      dialog.alert(t('저장되었습니다. (신규 {0} · 수정 {1} · 삭제 {2})', response.created, response.updated, response.deleted), () => onSaved?.());
    } finally {
      setSaving(false);
    }
  };

  // ---------------------------------------------------------------- 입력칸
  const textInput = (row: BulkRow, key: string, disabled: boolean, extra: { className?: string; list?: string; placeholder?: string } = {}) => (
    <input type="text" aria-label={key} className={`form-control${extra.className ? ` ${extra.className}` : ''}`} list={extra.list} placeholder={extra.placeholder}
      value={row.fields[key] ?? ''} disabled={disabled} onChange={(e) => setFieldValue(row, key, e.target.value)} />
  );
  const numberInput = (row: BulkRow, key: string, disabled: boolean) => (
    <input type="number" min={0} aria-label={key} className="form-control" value={row.fields[key] ?? ''} disabled={disabled}
      onKeyDown={blockNonNumericKeys} onChange={(e) => setFieldValue(row, key, e.target.value === '' ? null : e.target.value)} />
  );
  const checkInput = (row: BulkRow, key: string, disabled: boolean, title: string, forced = false) => (
    <input type="checkbox" title={title} aria-label={title} className="form-check-input" checked={forced || row.fields[key] === 'Y'} disabled={disabled}
      onChange={(e) => setFlag(row, key, e.target.checked)} />
  );

  let visibleIndex = 0;

  return (
    <div className="col-bulk">
      <p className="form-help col-bulk-help">
        {t('승인된 컬럼은 잠겨 있습니다. 승인 전 컬럼은 바로 고치거나 순서를 바꾸고 삭제할 수 있습니다. 컬럼명(용어)을 바꾸려면 삭제 후 다시 추가하세요. "속성 자동 추천"을 켜 두면 여부/등록 일시/등록자/첫 식별자 컬럼의 NOT NULL·기본값·PK 를 미리 채웁니다.')}
      </p>

      <section className="col-bulk-search">
        <div className="col-bulk-search-head">
          <div className="col-bulk-modes">
            {([['search', t('검색해서 추가')], ['paste', t('붙여넣기로 추가')], ['copy', t('다른 테이블에서 복사')]] as const).map(([m, label]) => (
              <button key={m} type="button" aria-pressed={mode === m} className={`btn btn-sm col-bulk-mode${mode === m ? ' is-active' : ''}`} onClick={() => setMode(m)}>
                {label}
              </button>
            ))}
          </div>
          <div className="col-bulk-search-tools">
            <label className="col-bulk-flag" title={t('여부 → NOT NULL·기본값 N, 등록 일시 → NOT NULL·CURRENT_TIMESTAMP, 등록자 → NOT NULL, 첫 식별자 컬럼 → PK')}>
              <input type="checkbox" className="form-check-input" checked={recommend} onChange={(e) => { setRecommend(e.target.checked); saveRecommendSetting(e.target.checked); }} />
              <span>{t('속성 자동 추천')}</span>
            </label>
            <button type="button" className="btn btn-sm" title={t('프로젝트 공통 컬럼 세트(등록일시/등록자 등)를 표 끝에 한 번에 추가합니다.')} onClick={addCommonColumns}>{t('공통 컬럼 추가')}</button>
            {commonEditable && (
              <button type="button" className="btn btn-sm" title={t('프로젝트 공통 컬럼 세트를 정합니다. (프로젝트 관리자)')} onClick={openCommonSet}>{t('공통 컬럼 세트 관리')}</button>
            )}
            <button type="button" className="btn btn-sm" onClick={createTermAndAdd}>{t('+ 새 표준용어 등록')}</button>
          </div>
        </div>

        {mode === 'search' && (
          <div className="col-bulk-pane">
            <div className="lookup-search-row">
              <select className="form-control" aria-label={t('표준용어 검색 기준')} value={searchType} onChange={(e) => setSearchType(e.target.value as TermSearchType)}>
                <option value="termName">{t('표준용어명')}</option>
                <option value="termAbbreviation">{t('영문약어명')}</option>
              </select>
              <input type="text" ref={searchInputRef} autoComplete="off" placeholder={t('예: 사용자 / USER_')} className="form-control" value={keyword}
                onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()} />
            </div>
            <div className="col-bulk-results" aria-live="polite">
              {!keyword.trim() ? (
                <p className="form-help">{t('표준용어명이나 영문약어명을 입력하면 바로 검색합니다. 여러 개를 체크해 한 번에 추가할 수 있습니다.')}</p>
              ) : results.length === 0 ? (
                <p className="table-name-error">{t('조건에 맞는 표준용어가 없습니다. [+ 새 표준용어 등록]으로 바로 등록할 수 있습니다.')}</p>
              ) : null}
              {keyword.trim() && results.map((term) => {
                const already = usedColumnNames.has(term.commonStandardTermAbbreviation);
                return (
                  <label key={term.id} className={`col-bulk-result${already ? ' is-disabled' : ''}`}>
                    <input type="checkbox" className="form-check-input" disabled={already} checked={!already && checkedIds.has(term.id)}
                      onChange={(e) => setCheckedIds((prev) => {
                        const next = new Set(prev);
                        if (e.target.checked) next.add(term.id);
                        else next.delete(term.id);
                        return next;
                      })} />
                    <span className="gh-mono">{term.commonStandardTermAbbreviation}</span>
                    <span>{termLabel(term)}</span>
                    <span className="gh-muted">{term.dataType ?? '-'}</span>
                    {term.isApprovalYn !== 'Y' && <span className="gh-label gh-label-attention">{t('용어 미승인')}</span>}
                    {already && <span className="gh-muted">{t('이미 추가됨')}</span>}
                  </label>
                );
              })}
            </div>
            <div className="col-bulk-search-actions">
              <button type="button" className="btn btn-sm" disabled={selectableIds.length === 0}
                onClick={() => setCheckedIds(checkedCount === selectableIds.length ? new Set() : new Set(selectableIds))}>
                {t('전체 선택')}
              </button>
              <button type="button" className="btn btn-sm btn-primary" disabled={checkedCount === 0} onClick={addChecked}>
                {checkedCount > 0 ? t('선택 추가 ({0})', checkedCount) : t('선택 추가')}
              </button>
            </div>
          </div>
        )}

        {mode === 'paste' && (
          <div className="col-bulk-pane">
            <textarea className="form-control col-bulk-paste-text" rows={5} autoFocus value={pasteText} onChange={(e) => setPasteText(e.target.value)}
              placeholder={t('설계서/엑셀에서 컬럼(용어명 또는 영문약어명)을 한 줄에 하나씩 붙여넣으세요.\n예)\n사용자 아이디\n사용자_이름\nUSE_YN\n(엑셀에서 여러 칸을 복사하면 첫 칸만 씁니다)')} />
            <div className="col-bulk-search-actions">
              <button type="button" className="btn btn-sm" disabled={resolving} onClick={findPaste}>{t('찾기')}</button>
            </div>
            <div className="col-bulk-paste-results" aria-live="polite">
              {pasteItems.length > 0 && (
                <>
                  <p className="form-help">
                    {t('{0}줄 · 찾음 {1} · 여러 건 {2} · 없음 {3}', pasteItems.length, pasteCounts.MATCHED, pasteCounts.AMBIGUOUS, pasteCounts.NOT_FOUND)}
                    {pasteCounts.NOT_FOUND ? t(' — 없는 용어는 [등록]으로 바로 만들 수 있습니다.') : ''}
                  </p>
                  <ul className="col-bulk-paste-list">
                    {pasteView.map(({ item, term, already, duplicated, blocked, checked }, index) => (
                      <li key={index} className={`col-bulk-paste-item is-${item.status.toLowerCase()}${blocked ? ' is-disabled' : ''}`}>
                        <input type="checkbox" aria-label={t('{0} 추가', item.input)} className="form-check-input" checked={checked} disabled={blocked}
                          onChange={(e) => updatePasteItem(index, { checked: e.target.checked })} />
                        <span className="col-bulk-paste-input">{item.input}</span>
                        <span className="gh-muted">→</span>
                        {item.status === 'NOT_FOUND' ? (
                          <>
                            <span className="gh-label gh-label-danger">{t('용어 없음')}</span>
                            <button type="button" className="btn btn-sm" onClick={() => createTermForPaste(index)}>{t('등록')}</button>
                          </>
                        ) : (
                          <>
                            {item.status === 'AMBIGUOUS' ? (
                              <>
                                <select className="form-control col-bulk-paste-select" aria-label={t('{0} 용어 선택', item.input)} value={String(item.selectedId)}
                                  onChange={(e) => updatePasteItem(index, { selectedId: Number(e.target.value), checked: true })}>
                                  {item.terms.map((term) => (
                                    <option key={term.id} value={term.id}>
                                      {`${term.commonStandardTermAbbreviation} · ${term.commonStandardDomainName ?? '-'} · ${term.dataType ?? '-'}${term.isApprovalYn === 'Y' ? '' : t(' (미승인)')}`}
                                    </option>
                                  ))}
                                </select>
                                <span className="gh-label gh-label-attention">{t('{0}건 중 선택', item.terms.length)}</span>
                              </>
                            ) : (
                              <>
                                <span className="gh-mono">{term?.commonStandardTermAbbreviation}</span>
                                <span className="gh-muted">{term?.dataType ?? '-'}</span>
                                {term && term.isApprovalYn !== 'Y' && <span className="gh-label gh-label-attention">{t('용어 미승인')}</span>}
                              </>
                            )}
                            {already && <span className="gh-muted">{t('이미 추가됨')}</span>}
                            {duplicated && <span className="gh-muted">{t('위에 같은 컬럼 있음')}</span>}
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
            <div className="col-bulk-search-actions">
              <button type="button" className="btn btn-sm btn-primary" disabled={pasteCheckedCount === 0} onClick={addPasted}>
                {pasteCheckedCount > 0 ? t('체크한 용어 추가 ({0})', pasteCheckedCount) : t('체크한 용어 추가')}
              </button>
            </div>
          </div>
        )}

        {mode === 'copy' && (
          <div className="col-bulk-pane">
            <input type="text" autoComplete="off" placeholder={t('복사할 테이블명 또는 일부 (예: TB_USER)')} className="form-control" autoFocus value={copyKeyword}
              onChange={(e) => setCopyKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()} />
            <select className="form-control lookup-select" size={4} aria-label={t('복사할 테이블 선택')} hidden={copyTables.length === 0 || !copyKeyword.trim()}
              value={copyTable ? String(copyTable.id) : ''} onChange={(e) => selectCopyTable(copyTables.find((t) => String(t.id) === e.target.value))}>
              {copyTables.map((t) => <option key={t.id} value={t.id}>{t.tableKorName ? `${t.tableName} · ${t.tableKorName}` : t.tableName}</option>)}
            </select>
            <div className="col-bulk-results col-bulk-copy-results" aria-live="polite">
              {copyMessage && <p className="table-name-error">{copyMessage}</p>}
              {copyTable && copyColumns.length === 0 && <p className="form-help">{t('{0} 에 등록된 컬럼이 없습니다.', copyTable.tableName)}</p>}
              {copyTable && copyColumns.map((col) => {
                const noTerm = !col.standardTerms?.id;
                const already = usedColumnNames.has(col.columnName);
                const disabled = noTerm || already;
                return (
                  <label key={col.id} className={`col-bulk-result${disabled ? ' is-disabled' : ''}`}>
                    <input type="checkbox" className="form-check-input" disabled={disabled} checked={!disabled && copyChecked.has(col.id)}
                      onChange={(e) => setCopyChecked((prev) => {
                        const next = new Set(prev);
                        if (e.target.checked) next.add(col.id);
                        else next.delete(col.id);
                        return next;
                      })} />
                    <span className="gh-mono">{col.columnName}</span>
                    <span>{col.columnDesc ?? ''}</span>
                    <span className="gh-muted">{`${col.dataType ?? '-'}${col.maxLength ? `(${col.maxLength})` : ''}`}</span>
                    {col.isPk === 'Y' && <span className="gh-label gh-label-accent">PK</span>}
                    {noTerm && <span className="gh-muted">{t('표준용어 연결 없음')}</span>}
                    {already && <span className="gh-muted">{t('이미 추가됨')}</span>}
                  </label>
                );
              })}
            </div>
            <div className="col-bulk-search-actions">
              <button type="button" className="btn btn-sm" disabled={copyCheckable.length === 0}
                onClick={() => setCopyChecked(copyCheckedCount === copyCheckable.length ? new Set() : new Set(copyCheckable))}>
                {t('전체 선택')}
              </button>
              <button type="button" className="btn btn-sm btn-primary" disabled={copyCheckedCount === 0} onClick={addCopied}>
                {copyCheckedCount > 0 ? t('체크한 컬럼 복사 ({0})', copyCheckedCount) : t('체크한 컬럼 복사')}
              </button>
            </div>
          </div>
        )}
      </section>

      {generalErrors.length > 0 && (
        <div className="col-bulk-general-error">
          {generalErrors.map((m, i) => <p key={i} className="table-name-error">{m}</p>)}
        </div>
      )}

      <div className="col-bulk-table-wrap" ref={tableWrapRef}>
        <table className="col-bulk-table">
          <thead>
            <tr>
              {['#', '', t('컬럼명'), t('컬럼 설명'), t('데이터 타입'), t('길이'), 'PK', 'NULL', t('기본값'), '', ''].map((h, i) => (
                <th key={i} className={['', 'col-move', '', '', '', 'col-num', 'col-chk', 'col-chk', '', 'col-icon', 'col-icon'][i] || undefined}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={11} className="col-bulk-empty">{t('등록된 컬럼이 없습니다. 위에서 표준용어를 검색해 추가하세요.')}</td>
              </tr>
            )}
            {rows.map((row, index) => {
              const locked = row.approved;
              const disabled = locked || !!row.deleted;
              const number = row.deleted ? null : ++visibleIndex;
              const modified = !row.isNew && !row.approved && !row.deleted && isModified(row);
              const className = [locked ? 'is-locked' : '', row.isNew ? 'is-new' : '', row.deleted ? 'is-deleted' : '', row.error ? 'has-error' : ''].filter(Boolean).join(' ');
              return (
                <Fragment key={row.key}>
                  <tr data-key={row.key} className={className || undefined}>
                    <td className="col-num-cell">{number ?? '-'}</td>
                    <td className="col-move">
                      {!row.deleted && (
                        <>
                          <button type="button" title={t('위로')} aria-label={t('위로')} className="btn btn-sm col-bulk-icon" disabled={!canMove(index, -1)} onClick={() => move(index, -1)}>
                            <ChevronUpIcon size={14} />
                          </button>
                          <button type="button" title={t('아래로')} aria-label={t('아래로')} className="btn btn-sm col-bulk-icon" disabled={!canMove(index, 1)} onClick={() => move(index, 1)}>
                            <ChevronDownIcon size={14} />
                          </button>
                        </>
                      )}
                    </td>
                    <td className="col-name">
                      <span className="gh-mono">{row.columnName}</span>
                      {row.isNew ? <span className="gh-label gh-label-accent">{t('신규')}</span>
                        : locked ? <span className="gh-label gh-label-success">{t('승인')}</span>
                          : row.deleted ? <span className="gh-label gh-label-danger">{t('삭제 예정')}</span>
                            : modified ? <span className="gh-label gh-label-attention">{tc('상태', '수정')}</span> : null}
                      {row.termNotApproved && <span className="gh-label gh-label-attention">{t('용어 미승인')}</span>}
                      {row.source && !row.deleted && <span className="gh-label col-bulk-source" title={row.source.title}>{row.source.label}</span>}
                      {row.recommended?.length && !row.deleted ? (
                        <span className="gh-label gh-label-done col-bulk-recommended" title={t('자동 추천 적용 : {0} (표에서 바로 바꿀 수 있습니다)', row.recommended.join(' / '))}>{t('추천')}</span>
                      ) : null}
                    </td>
                    <td>{textInput(row, 'columnDesc', disabled)}</td>
                    <td>{textInput(row, 'dataType', disabled, { className: 'gh-mono', list: 'colBulkDataTypes' })}</td>
                    <td className="col-num">{numberInput(row, 'maxLength', disabled)}</td>
                    <td className="col-chk">{checkInput(row, 'isPk', disabled, 'PK')}</td>
                    <td className="col-chk">{checkInput(row, 'isNullable', disabled || row.fields.isPk === 'Y', t('NULL 허용'))}</td>
                    <td>{textInput(row, 'defaultValue', disabled, { className: 'gh-mono' })}</td>
                    <td className="col-icon">
                      {!row.deleted && (
                        <button type="button" title={row.expanded ? t('상세 닫기') : t('상세 (정밀도/참조 테이블/속성)')} aria-expanded={!!row.expanded} className="btn btn-sm col-bulk-icon"
                          onClick={() => updateRow(row.key, (r) => ({ ...r, expanded: !r.expanded }))}>
                          {row.expanded ? '▴' : '⋯'}
                        </button>
                      )}
                    </td>
                    <td className="col-icon">
                      {row.deleted ? (
                        <button type="button" title={t('삭제 취소')} className="btn btn-sm col-bulk-icon" onClick={() => restoreRow(row)}>↺</button>
                      ) : !locked ? (
                        <button type="button" title={t('삭제')} className="btn btn-sm col-bulk-icon col-bulk-remove" onClick={() => removeRow(index)}>✕</button>
                      ) : null}
                    </td>
                  </tr>
                  {row.expanded && !row.deleted && (
                    <tr className="col-bulk-detail-row">
                      <td colSpan={2} />
                      <td colSpan={9}>
                        <div className="col-bulk-detail">
                          <label className="col-bulk-field"><span>{t('정밀도')}</span>{numberInput(row, 'precision', locked)}</label>
                          <label className="col-bulk-field"><span>{t('소수 자릿수')}</span>{numberInput(row, 'scale', locked)}</label>
                          {/* 참조 테이블명 : 승인된 테이블 자동완성 (승인된 컬럼은 잠겨 있으므로 조회하지 않는다) */}
                          <div className="col-bulk-field">
                            <span>{t('참조 테이블명')}</span>
                            <RefTableLookup value={row.fields.refTableName ?? ''} disabled={locked} placeholder={t('예: TB_USER')} ariaLabel="refTableName"
                              onChange={(v) => setFieldValue(row, 'refTableName', v)} />
                          </div>
                          <label className="col-bulk-field"><span>{t('데이터 예시')}</span>{textInput(row, 'example', locked)}</label>
                        </div>
                        <div className="col-bulk-flags">
                          {DETAIL_FLAGS.map(([key, label]) => {
                            // PK 는 필수/고유값으로 저장된다 (서버와 같은 규칙)
                            const forced = row.fields.isPk === 'Y' && (key === 'isRequired' || key === 'isUnique');
                            return (
                              <label className="col-bulk-flag" key={key}>
                                {checkInput(row, key, locked || forced, label, forced)}
                                <span>{label}</span>
                              </label>
                            );
                          })}
                        </div>
                      </td>
                    </tr>
                  )}
                  {row.error && (
                    <tr className="col-bulk-error-row">
                      <td colSpan={11}><p className="table-name-error">{row.error}</p></td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      <datalist id="colBulkDataTypes">
        {DATA_TYPES.map((t) => <option key={t} value={t} />)}
      </datalist>

      <div className="gh-dialog-actions col-bulk-actions">
        <span className="col-bulk-summary">
          {[t('신규 {0}', counts.created), t('수정 {0}', counts.updated), t('삭제 {0}', counts.deleted), ...(counts.reordered ? [t('순서 변경')] : [])].join(' · ')}
        </span>
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="button" className="btn btn-primary" disabled={saving || !isDirty} onClick={onSave}>{t('일괄 저장')}</button>
      </div>
    </div>
  );
}
