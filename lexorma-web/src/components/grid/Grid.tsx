import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
  type Row as TableRow,
  type RowSelectionState,
  type SortingState,
  type VisibilityState,
} from '@tanstack/react-table';
import { api } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { useAuth } from '../../hooks/useAuth';
import { useDialog } from '../dialog/DialogProvider';
import { t, tc, tv } from '../../lib/i18n';

/**
 * 화면 정의 컬럼 그리드 (TanStack Table v8 기반, 마크업 / CSS 는 예전 DataTables 와 같다)
 *
 * - columns prop → 화면별 컬럼 정의
 * - GET {dataUrl}/data?page&size&sort&searchColumn&searchQuery → { data, recordsTotal }
 * - crudActions : C=추가, U=저장, M=수정, D=삭제, A=승인, N=승인취소, P=출력
 *   소문자는 승인 권한(/api/auth/me 의 approvalAvailable)이 있을 때만 보인다.
 */

export type Row = Record<string, any>;

export interface GridColumn {
  columnName: string;
  column: string;
  columnType: 'STRING' | 'NUMBER' | 'DATE' | string;
  rowType?: 'TEXT' | 'INPUT' | 'BUTTON' | 'SELECT' | 'DATE' | 'CHECKBOX' | string | null;
  options?: string[] | null;
  isSearch?: boolean;
  isFilter?: boolean;
  isVisible?: boolean;
  /** 컬럼 폭 (예: '20%', 120). 하나라도 주면 표를 고정 배치로 그려 데이터에 따라 폭이 바뀌지 않는다 */
  width?: string | number;
}

export type GridAction = 'add' | 'save' | 'modified' | 'delete' | 'active' | 'unactive' | 'print';

const ACTION_MAP: Record<string, GridAction> = { C: 'add', U: 'save', M: 'modified', D: 'delete', A: 'active', N: 'unactive', P: 'print' };

const ACTION_BUTTONS: { action: GridAction; label: string; className: string }[] = [
  { action: 'active', label: tc('버튼', '승인'), className: 'btn btn-primary' },
  { action: 'unactive', label: t('승인취소'), className: 'btn btn-secondary' },
  { action: 'add', label: t('추가'), className: 'btn btn-primary' },
  { action: 'modified', label: t('수정'), className: 'btn btn-primary' },
  { action: 'save', label: t('저장'), className: 'btn btn-primary' },
  { action: 'delete', label: t('삭제'), className: 'btn btn-secondary' },
  { action: 'print', label: t('출력'), className: 'btn btn-secondary' },
];

const PAGE_SIZES = [10, 25, 50, 100];

export interface GridHandle {
  /** 현재 조건으로 다시 조회 (체크 해제) */
  reload: () => void;
  /** 검색어를 비우고 다시 조회 */
  resetSearch: () => void;
  /** 체크된 행 */
  getChecked: () => Row[];
  /** 체크된 행 (없으면 알림 후 null) */
  getCheckedOrAlert: () => Row[] | null;
  /** 현재 페이지의 모든 행 (셀에서 고친 값 반영) */
  getEditedRows: () => Row[];
  columns: GridColumn[];
}

export interface GridProps {
  id: string;
  dataUrl: string;
  columns: GridColumn[];
  crudActions?: string;
  /** 버튼 그룹 앞에 붙는 화면 버튼 (예전 .grid-extra-actions) */
  extraActions?: ReactNode;
  onAction?: Partial<Record<GridAction, () => void>>;
  /** 행 클릭 : (행, 컬럼 정의, 승인 권한 여부) */
  onRowClick?: (row: Row, columns: GridColumn[], isManager: boolean) => void;
  /** BUTTON 셀 클릭 */
  onCellButton?: (field: string, row: Row) => void;
  /** 셀 꾸미기 : undefined 를 반환하면 기본 표시 */
  renderCell?: (field: string, row: Row) => ReactNode | undefined;
  rowClassName?: (row: Row) => string | undefined;
  /** 처음 정렬 (서버 컬럼명). 없으면 DataTables 처럼 첫 컬럼 오름차순 */
  defaultSort?: { column: string; desc?: boolean };
  /** 조회 API 에 함께 보내는 조건 (예: 스키마 필터). 바뀌면 첫 페이지부터 다시 조회한다 */
  params?: Record<string, string>;
  /** 검색 줄 앞에 붙는 조회 조건 (예: 스키마 select). 값은 params 로 넘긴다 */
  searchFilters?: ReactNode;
  /** 처음 열 때의 검색 조건 (다른 화면에서 넘어온 경우) */
  initialSearch?: { column: string; query: string };
  /** 조회 결과가 없을 때의 안내 (검색어 없이 비어 있을 때) */
  emptyText?: string;
}

interface PageData {
  data: Row[];
  recordsTotal: number;
}

export const Grid = forwardRef<GridHandle, GridProps>(function Grid(props, ref) {
  const { id, dataUrl, columns, crudActions = '', extraActions, onAction, onRowClick, onCellButton, renderCell, rowClassName, defaultSort, params, searchFilters, initialSearch, emptyText } = props;
  const { approvalAvailable } = useAuth();
  const dialog = useDialog();

  const searchColumns = useMemo(() => columns.filter((c) => c.isSearch), [columns]);

  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: 10 });
  // null = 처음 정렬 : defaultSort, 없으면 첫 컬럼 오름차순 (숨김 컬럼이어도 기준이 된다. 예: 컬럼 관리의 sortOrder)
  const [sortingState, setSortingState] = useState<SortingState | null>(null);
  const [searchColumn, setSearchColumn] = useState(initialSearch?.column ?? '');
  const [searchInput, setSearchInput] = useState(initialSearch?.query ?? '');
  const [search, setSearch] = useState({ column: initialSearch?.column ?? '', query: initialSearch?.query ?? '' });
  const paramsKey = JSON.stringify(params ?? {});
  const [reloadKey, setReloadKey] = useState(0);
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [edits, setEdits] = useState<Record<number, Row>>({});
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => {
    if (searchColumns.length > 0 && !searchColumns.some((c) => c.column === searchColumn)) {
      setSearchColumn(searchColumns[0].column);
    }
  }, [searchColumns, searchColumn]);

  const sorting = useMemo<SortingState>(() => {
    if (sortingState) return sortingState;
    if (columns.length === 0) return [];
    const index = defaultSort ? columns.findIndex((c) => c.column === defaultSort.column) : -1;
    return index >= 0
      ? [{ id: columnId(columns[index], index), desc: !!defaultSort?.desc }]
      : [{ id: columnId(columns[0], 0), desc: false }];
  }, [sortingState, columns, defaultSort?.column, defaultSort?.desc]);

  const sort = useMemo(() => {
    const order = sorting[0];
    const index = order ? columns.findIndex((c, i) => columnId(c, i) === order.id) : -1;
    if (index < 0) return '';
    return `${columns[index].column || 'timestamp'},${order.desc ? 'desc' : 'asc'}`;
  }, [sorting, columns]);

  const { pageIndex: page, pageSize: size } = pagination;

  const dataQuery = useQuery({
    queryKey: ['grid-data', dataUrl, page, size, sort, search.column, search.query, paramsKey, reloadKey],
    queryFn: () => {
      const query = new URLSearchParams({ ...params, page: String(page), size: String(size), sort });
      if (search.column) query.set('searchColumn', search.column);
      query.set('searchQuery', search.query);
      return api<PageData>(`${dataUrl}/data?${query}`);
    },
    enabled: columns.length > 0,
    placeholderData: (prev) => prev,
    staleTime: 0,
  });

  const rows = useMemo(() => dataQuery.data?.data ?? [], [dataQuery.data]);
  const total = dataQuery.data?.recordsTotal ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / size));

  const clearRowState = useCallback(() => {
    setRowSelection({});
    setEdits({});
    setSelected(null);
  }, []);

  // 새 데이터가 오면 체크 / 셀 편집 / 선택을 비운다 (DataTables 가 행을 다시 그리던 것과 같음)
  useEffect(() => {
    clearRowState();
  }, [dataQuery.data, clearRowState]);

  // 조회 조건(params)이 바뀌면 첫 페이지부터
  useEffect(() => {
    setPagination((p) => (p.pageIndex === 0 ? p : { ...p, pageIndex: 0 }));
  }, [paramsKey]);

  const reload = useCallback(() => {
    clearRowState();
    setReloadKey((k) => k + 1);
  }, [clearRowState]);

  const resetSearch = useCallback(() => {
    clearRowState();
    setSearchInput('');
    setSearch((s) => ({ ...s, query: '' }));
    setReloadKey((k) => k + 1);
  }, [clearRowState]);

  const columnDefs = useMemo<ColumnDef<Row>[]>(
    () => columns.map((col, i) => ({
      id: columnId(col, i),
      accessorFn: (row) => row[col.column],
      enableSorting: col.isFilter !== false && col.rowType !== 'CHECKBOX',
      meta: col,
    })),
    [columns],
  );

  // 버튼 표시 : 대문자는 항상, 소문자는 승인 권한이 있을 때만
  const hasLowercase = crudActions.split('').some((a) => a === a.toLowerCase());
  const isManager = hasLowercase ? approvalAvailable : false;
  const visibleActions = new Set<GridAction>();
  crudActions.split('').forEach((a) => {
    const action = ACTION_MAP[a.toUpperCase()];
    if (!action) return;
    if (a !== a.toLowerCase() || approvalAvailable) visibleActions.add(action);
  });
  const hasActions = visibleActions.size > 0;

  // 쓸 수 있는 버튼이 하나도 없으면(조회 전용) 고를 일이 없으므로 체크박스 열도 숨긴다
  const columnVisibility = useMemo<VisibilityState>(
    () => Object.fromEntries(columns.map((col, i) => [columnId(col, i), col.isVisible !== false && (hasActions || col.rowType !== 'CHECKBOX')])),
    [columns, hasActions],
  );

  // 서버 페이징 / 정렬 : 조회는 위 dataQuery 가 하고, 테이블은 상태(페이지 / 정렬 / 체크 / 컬럼 표시)만 관리한다
  const table = useReactTable({
    data: rows,
    columns: columnDefs,
    state: { pagination, sorting, rowSelection, columnVisibility },
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualSorting: true,
    pageCount,
    enableMultiSort: false,
    enableSortingRemoval: false, // DataTables 처럼 오름차순 ↔ 내림차순만 토글
    sortDescFirst: false,
    onPaginationChange: setPagination,
    onSortingChange: (updater) => {
      setSortingState(typeof updater === 'function' ? updater(sorting) : updater);
      setPagination((p) => ({ ...p, pageIndex: 0 }));
    },
    onRowSelectionChange: setRowSelection,
  });

  const editedRows = useCallback(() => rows.map((row, i) => ({ ...row, ...edits[i] })), [rows, edits]);

  const getChecked = useCallback(
    () => rows.filter((_, i) => rowSelection[String(i)]),
    [rows, rowSelection],
  );

  useImperativeHandle(
    ref,
    () => ({
      reload,
      resetSearch,
      getChecked,
      getCheckedOrAlert: () => {
        const list = getChecked();
        if (list.length > 0) return list;
        dialog.alert(t('체크된 데이터가 존재하지 않습니다.'));
        return null;
      },
      getEditedRows: editedRows,
      columns,
    }),
    [reload, resetSearch, getChecked, editedRows, columns, dialog],
  );

  const selectedSearch = searchColumns.find((c) => c.column === searchColumn);
  const isNumberSearch = selectedSearch?.columnType === 'NUMBER';

  const doSearch = () => {
    clearRowState();
    setPagination((p) => ({ ...p, pageIndex: 0 }));
    setSearch({ column: searchColumn, query: searchInput });
    setReloadKey((k) => k + 1);
  };

  const renderValue = (col: GridColumn, row: TableRow<Row>): ReactNode => {
    const data = row.original[col.column];
    const custom = renderCell?.(col.column, row.original);
    if (custom !== undefined) return custom;

    const edited = edits[row.index]?.[col.column];
    const setEdit = (value: string) =>
      setEdits((prev) => ({ ...prev, [row.index]: { ...prev[row.index], [col.column]: value } }));

    switch (col.rowType) {
      case 'INPUT':
      case 'DATE':
        return (
          <input
            type="text"
            className={`form-control ${col.rowType === 'DATE' ? 'datepicker' : 'edit-input'}`}
            value={edited ?? data ?? ''}
            onChange={(e) => setEdit(e.target.value)}
          />
        );
      case 'BUTTON':
        return (
          <button type="button" className="btn btn-sm btn-primary action-btn" onClick={() => onCellButton?.(col.column, row.original)}>
            {typeof data === 'string' ? tv(data) : data}
          </button>
        );
      case 'SELECT':
        return (
          <select className="form-control edit-select" value={edited ?? data ?? ''} onChange={(e) => setEdit(e.target.value)}>
            {(col.options ?? []).map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        );
      case 'CHECKBOX':
        return <input type="checkbox" className="row-checkbox" checked={row.getIsSelected()} onChange={row.getToggleSelectedHandler()} />;
      default:
        if (col.columnType === 'DATE') return <p>{formatDateTime(data)}</p>;
        if (col.columnType === 'STRING' && (data === 0 || data === '0')) return data;
        return col.rowType === 'TEXT' ? <p>{data ?? ''}</p> : (data ?? '');
    }
  };

  const headers = table.getHeaderGroups()[0]?.headers ?? [];
  // 폭을 정한 그리드 : 조회 결과·페이지가 바뀌어도 컬럼 폭이 그대로다
  const fixed = columns.some((c) => c.width != null);
  const tableRows = table.getRowModel().rows;

  return (
    <div>
      <div className="grid-header">
        {searchColumns.length > 0 && (
          <div className="search-container">
            {searchFilters}
            <select className="search-column-select" value={searchColumn} onChange={(e) => { setSearchColumn(e.target.value); setSearchInput(''); }}>
              {searchColumns.map((c) => (
                <option key={c.column} value={c.column}>
                  {c.columnName}
                </option>
              ))}
            </select>
            <input
              type={isNumberSearch ? 'number' : 'text'}
              className={`search-input ${isNumberSearch ? 'search-input-number' : 'search-input-text'}`}
              placeholder={isNumberSearch ? t('숫자만 입력하세요') : t('검색어 입력')}
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onKeyDown={(e) => {
                if (isNumberSearch && ['e', 'E', '+', '-'].includes(e.key)) e.preventDefault();
                if (e.key === 'Enter') doSearch();
              }}
            />
            <button type="button" className="grd-select" onClick={doSearch}>
              {t('조회')}
            </button>
          </div>
        )}

        <div className="button-container">
          {extraActions && <div className="grid-extra-actions">{extraActions}</div>}
          {ACTION_BUTTONS.filter((b) => visibleActions.has(b.action)).map((b) => (
            <button key={b.action} type="button" className={`grd-${b.action} ${b.className}`} onClick={() => onAction?.[b.action]?.()}>
              {b.label}
            </button>
          ))}
        </div>
      </div>

      <hr className="grid-divider" />

      <div className="dt-container">
        <div className="dt-layout-row">
          <div className="dt-layout-cell dt-layout-start">
            <div className="dt-length">
              <label>
                {t('페이지당')}{' '}
                <select className="dt-input" value={size} onChange={(e) => setPagination({ pageIndex: 0, pageSize: Number(e.target.value) })}>
                  {PAGE_SIZES.map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>{' '}
                {t('행 표시')}
              </label>
            </div>
          </div>
        </div>

        <div className="dt-layout-row dt-layout-table">
          <div className="dt-layout-cell dt-layout-full">
            <table id={id} className={`table table-striped table-bordered dataTable${fixed ? ' grid-fixed' : ''}`}>
              {fixed && (
                <colgroup>
                  {headers.map((header) => <col key={header.id} style={{ width: (header.column.columnDef.meta as GridColumn).width }} />)}
                </colgroup>
              )}
              <thead>
                <tr>
                  {headers.map((header) => {
                    const col = header.column.columnDef.meta as GridColumn;
                    if (col.rowType === 'CHECKBOX') {
                      return (
                        <th key={header.id} className="grid-checkbox-header text-center" style={{ width: 40 }}>
                          <input type="checkbox" className="header-checkbox" checked={rows.length > 0 && table.getIsAllRowsSelected()}
                            onChange={table.getToggleAllRowsSelectedHandler()} />
                        </th>
                      );
                    }
                    const orderable = header.column.getCanSort();
                    const ordering = header.column.getIsSorted();
                    const classes = ['text-start'];
                    if (orderable) classes.push('dt-orderable-asc', 'dt-orderable-desc');
                    else classes.push('no-sort');
                    if (ordering) classes.push(`dt-ordering-${ordering}`);
                    return (
                      <th
                        key={header.id}
                        className={classes.join(' ')}
                        onClick={orderable ? header.column.getToggleSortingHandler() : undefined}
                        aria-sort={ordering === 'asc' ? 'ascending' : ordering === 'desc' ? 'descending' : undefined}
                      >
                        <div className="dt-column-header">
                          <span className="dt-column-title">{col.columnName}</span>
                          {orderable && <span className="dt-column-order" />}
                        </div>
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {tableRows.length === 0 ? (
                  <tr>
                    <td colSpan={Math.max(headers.length, 1)} className="dt-empty">
                      {dataQuery.isPending
                        ? t('조회 중입니다.')
                        : search.query
                          ? t('검색된 데이터가 없습니다.')
                          : (emptyText ?? t('조회된 데이터가 없습니다.'))}
                    </td>
                  </tr>
                ) : (
                  tableRows.map((row) => (
                    <tr
                      key={row.original.id ?? row.index}
                      className={[selected === row.index ? 'selected' : '', rowClassName?.(row.original) ?? ''].filter(Boolean).join(' ') || undefined}
                      onClick={(e) => {
                        if ((e.target as HTMLElement).closest('input, button, select, label')) return; // 셀 입력은 행 선택 아님
                        setSelected(row.index);
                        onRowClick?.(row.original, columns, isManager);
                      }}
                    >
                      {row.getVisibleCells().map((cell) => {
                        const col = cell.column.columnDef.meta as GridColumn;
                        return (
                          <td key={cell.id} className={col.rowType === 'CHECKBOX' ? 'text-center' : 'text-start'}>
                            {renderValue(col, row)}
                          </td>
                        );
                      })}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div className="dt-layout-row">
          <div className="dt-layout-cell dt-layout-end">
            <Paging page={page} pageCount={pageCount} onChange={table.setPageIndex} />
          </div>
        </div>
      </div>
    </div>
  );
});

/**
 * TanStack Table 컬럼 id : 순번을 붙여 항상 고유하게 만든다.
 * 서버 컬럼명은 비어 있거나 겹칠 수 있다 (예: 컬럼 관리의 체크박스 컬럼과 숨김 '정렬 순서' 컬럼이 둘 다 sortOrder)
 */
function columnId(col: GridColumn, index: number) {
  return `${index}:${col.column}`;
}

function Paging({ page, pageCount, onChange }: { page: number; pageCount: number; onChange: (page: number) => void }) {
  const button = (label: ReactNode, target: number, disabled: boolean, current = false, key?: string) => (
    <button
      key={key ?? String(label)}
      type="button"
      className={`dt-paging-button${current ? ' current' : ''}${disabled ? ' disabled' : ''}`}
      disabled={disabled}
      aria-current={current ? 'page' : undefined}
      onClick={() => onChange(target)}
    >
      {label}
    </button>
  );

  // 최대 7칸 (DataTables 와 같은 생략 규칙)
  const numbers: (number | 'gap')[] = [];
  if (pageCount <= 7) {
    for (let i = 0; i < pageCount; i++) numbers.push(i);
  } else if (page < 4) {
    numbers.push(0, 1, 2, 3, 4, 'gap', pageCount - 1);
  } else if (page > pageCount - 5) {
    numbers.push(0, 'gap', pageCount - 5, pageCount - 4, pageCount - 3, pageCount - 2, pageCount - 1);
  } else {
    numbers.push(0, 'gap', page - 1, page, page + 1, 'gap', pageCount - 1);
  }

  return (
    <div className="dt-paging">
      <nav aria-label="pagination">
        {button(t('처음'), 0, page === 0, false, 'first')}
        {button(t('이전'), page - 1, page === 0, false, 'prev')}
        {numbers.map((n, i) =>
          n === 'gap' ? (
            <span key={`gap-${i}`} className="ellipsis">
              …
            </span>
          ) : (
            button(n + 1, n, false, n === page, `p-${n}`)
          ),
        )}
        {button(t('다음'), page + 1, page >= pageCount - 1, false, 'next')}
        {button(t('마지막'), pageCount - 1, page >= pageCount - 1, false, 'last')}
      </nav>
    </div>
  );
}
