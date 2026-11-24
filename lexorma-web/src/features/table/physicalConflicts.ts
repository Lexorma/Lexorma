import { api, ApiError, enc } from '../../lib/api';
import { baseQuery, type Deletion } from '../../lib/deletion';
import { t } from '../../lib/i18n';
import type { DialogApi } from '../../components/dialog/DialogProvider';
import { runListWithReview, runWithReview, type ListItem, type Snapshot } from '../../components/conflict/conflictActions';

/**
 * 테이블·컬럼 목록의 일괄 승인·삭제 충돌 복구 (M8-C3). 서버는 행마다 기준값을 비교해 하나라도 다르면 전체 롤백(409)하므로,
 * 검토 대화상자가 바뀐 행만 보여 주고 사용자가 최신 기준값으로 다시 실행한다 (선택한 행은 그대로).
 */

/** 그리드 행 (서버 DTO 그대로 — id·modifiedAt·isApproval 을 본다) */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type GridRow = Record<string, any>;

const approvedOf = (row: GridRow) => row.isApproval === 'Y' || row.isApproval === true;

export function rowItems(rows: GridRow[], labelKey: string): ListItem[] {
  return rows.map((row) => ({
    id: Number(row.id), label: String(row[labelKey] ?? row.id),
    seen: { modifiedAt: (row.modifiedAt as string | null | undefined) ?? null, approved: approvedOf(row) },
  }));
}

/** 테이블들의 지금 상태 (상세 조회 — 없어진 테이블은 빠진다) */
async function loadTables(ids: number[]): Promise<Map<number, Snapshot>> {
  const found = await Promise.all(ids.map((id) => api<GridRow>(`/api/selectTable/detail/${enc(id)}`, { silent: true }).catch(() => null)));
  return new Map(found.filter((r): r is GridRow => !!r && r.id != null)
    .map((r) => [Number(r.id), { modifiedAt: (r.modifiedAt as string | null) ?? null, approved: approvedOf(r) }]));
}

/** 테이블 삭제 확인 조회 (수정 일시·구성 기준값·함께 지워질 수) */
export async function loadTableDeletions(ids: number[]): Promise<Map<number, Snapshot>> {
  const list = await api<Deletion[]>(`/api/deleteTable/preview?tableIds=${ids.join(',')}`, { silent: true });
  return new Map(list.map((d) => [d.id, { modifiedAt: d.modifiedAt, composition: d.composition, cascade: d.cascade }]));
}

/** 컬럼들의 지금 상태 : 소속 테이블마다 한 번 읽는다 */
async function loadColumns(rows: GridRow[]): Promise<Map<number, Snapshot>> {
  const tables = [...new Set(rows.map((r) => r.tableInfoId as number).filter((id) => id != null))];
  const result = new Map<number, Snapshot>();
  await Promise.all(tables.map(async (tableId) => {
    const columns = (await api<{ columnInfos: GridRow[] }>(`/api/selectColumn/tableColumn/${enc(tableId)}`, { silent: true }).catch(() => ({ columnInfos: [] as GridRow[] }))).columnInfos;
    columns.forEach((c) => result.set(Number(c.id), { modifiedAt: (c.modifiedAt as string | null) ?? null, approved: approvedOf(c) }));
  }));
  return result;
}

const intro = () => t('선택한 항목 중 다른 사용자가 바꾼 항목이 있어 아무것도 바뀌지 않았습니다.');

/** 테이블 일괄 승인·승인 취소 */
export function approveTables(dialog: DialogApi, rows: GridRow[], isApproval: boolean, after: () => void, done: () => void) {
  return runListWithReview({
    dialog, title: isApproval ? t('테이블 승인') : t('테이블 승인 취소'), intro: intro(), items: rowItems(rows, 'tableName'), targetApproval: isApproval,
    load: () => loadTables(rows.map((r) => Number(r.id))),
    act: async (states) => {
      const body = rows.map((row) => ({ ...row, modifiedAt: states.get(Number(row.id))?.modifiedAt ?? row.modifiedAt }));
      await api(`/api/saveTable/${isApproval}`, { method: 'PATCH', body, conflict: 'handled' });
    },
    retryLabel: isApproval ? t('최신 내용으로 승인') : t('최신 내용으로 승인 취소'), onDone: done, onClose: after,
  });
}

/** 테이블 일괄 삭제 (확인 조회의 구성 기준값) */
export function deleteTables(dialog: DialogApi, rows: GridRow[], deletions: Deletion[], after: () => void, done: () => void) {
  const byId = new Map(deletions.map((d) => [d.id, d]));
  const items: ListItem[] = rows.map((row) => ({
    id: Number(row.id), label: String(row.tableName ?? row.id),
    seen: { modifiedAt: (row.modifiedAt as string | null | undefined) ?? byId.get(Number(row.id))?.modifiedAt ?? null, composition: byId.get(Number(row.id))?.composition, cascade: byId.get(Number(row.id))?.cascade },
  }));
  return runListWithReview({
    dialog, title: t('테이블 삭제'), intro: t('삭제 대상 또는 함께 삭제될 항목이 그 사이 바뀌었습니다.'), items,
    load: () => loadTableDeletions(rows.map((r) => Number(r.id))),
    act: async (states) => {
      const body = rows.map((row) => ({ id: row.id, modifiedAt: states.get(Number(row.id))?.modifiedAt ?? null, composition: states.get(Number(row.id))?.composition ?? null }));
      await api('/api/deleteTable', { method: 'DELETE', body, conflict: 'handled' });
    },
    retryLabel: t('최신 구성으로 삭제'), onDone: done, onClose: after,
  });
}

/** 컬럼 일괄 승인·승인 취소 */
export function approveColumns(dialog: DialogApi, rows: GridRow[], isApproval: boolean, after: () => void, done: () => void) {
  return runListWithReview({
    dialog, title: isApproval ? t('컬럼 승인') : t('컬럼 승인 취소'), intro: intro(), items: rowItems(rows, 'columnName'), targetApproval: isApproval,
    load: () => loadColumns(rows),
    act: async (states) => {
      const body = rows.map((row) => ({ ...row, modifiedAt: states.get(Number(row.id))?.modifiedAt ?? row.modifiedAt }));
      await api(`/api/saveColumn/approval/${isApproval}`, { method: 'PATCH', body, conflict: 'handled' });
    },
    retryLabel: isApproval ? t('최신 내용으로 승인') : t('최신 내용으로 승인 취소'), onDone: done, onClose: after,
  });
}

/** 컬럼 일괄 삭제 */
export function deleteColumns(dialog: DialogApi, rows: GridRow[], after: () => void, done: () => void) {
  return runListWithReview({
    dialog, title: t('컬럼 삭제'), intro: intro(), items: rowItems(rows, 'columnName'),
    load: () => loadColumns(rows),
    act: async (states) => {
      const body = rows.map((row) => ({ ...row, modifiedAt: states.get(Number(row.id))?.modifiedAt ?? row.modifiedAt }));
      await api('/api/deleteColumn', { method: 'DELETE', body, conflict: 'handled' });
    },
    retryLabel: t('최신 내용으로 삭제'), onDone: done, onClose: after,
  });
}

// ------------------------------------------------------------------ 단건 (스키마 · FK · 인덱스 · 제약조건 · VIEW · ERD 테이블)

export type Kind = 'relation' | 'index' | 'constraint';

export interface TableModelPart {
  outgoingRelations: { id: number; relationName: string | null; isApproval: boolean | null; modifiedAt?: string | null; targetTableName: string; deleteRule?: string | null; updateRule?: string | null; columns: { sourceColumnName: string; targetColumnName: string }[] }[];
  indexes: { id: number; indexName: string; isApproval: boolean | null; modifiedAt?: string | null; columns: { columnName: string | null }[] }[];
  constraints: { id: number; constraintName: string; constraintType: string; checkExpression: string | null; isApproval: boolean | null; modifiedAt?: string | null; columns: { columnName: string | null }[] }[];
}

const notFound = () => new ApiError(400, t('대상을 찾을 수 없습니다.'), 'INVALID_PHYSICAL_MODEL_IS_NOT_DEFINED');

/** 테이블 물리 모델(GET /api/physical/table/{id})에서 관계(나가는)·인덱스·제약조건 하나의 상태 — 화면이 가진 모델로도, 새로 읽은 모델로도 만든다 */
export function keySnapshot(model: TableModelPart, kind: Kind, id: number): Snapshot {
  if (kind === 'relation') {
    const r = model.outgoingRelations.find((x) => x.id === id);
    if (!r) throw notFound();
    return { modifiedAt: r.modifiedAt ?? null, approved: !!r.isApproval, fields: [
      { key: 'name', label: t('관계명'), value: r.relationName ?? '' },
      { key: 'pairs', label: t('컬럼 짝'), value: r.columns.map((c) => `${c.sourceColumnName} → ${c.targetColumnName}`).join(', ') },
      { key: 'rules', label: t('FK 규칙'), value: `${r.deleteRule ?? 'NO_ACTION'} / ${r.updateRule ?? 'NO_ACTION'}` },
    ] };
  }
  if (kind === 'index') {
    const i = model.indexes.find((x) => x.id === id);
    if (!i) throw notFound();
    return { modifiedAt: i.modifiedAt ?? null, approved: !!i.isApproval, fields: [
      { key: 'name', label: t('인덱스명'), value: i.indexName },
      { key: 'columns', label: t('컬럼'), value: i.columns.map((c) => c.columnName ?? '').join(', ') },
    ] };
  }
  const c = model.constraints.find((x) => x.id === id);
  if (!c) throw notFound();
  return { modifiedAt: c.modifiedAt ?? null, approved: !!c.isApproval, fields: [
    { key: 'name', label: t('제약조건명'), value: c.constraintName },
    { key: 'definition', label: t('컬럼 / 조건식'), value: c.constraintType === 'CHECK' ? c.checkExpression ?? '' : c.columns.map((x) => x.columnName ?? '').join(', ') },
  ] };
}

export async function loadKey(kind: Kind, tableId: number, id: number): Promise<Snapshot> {
  return keySnapshot(await api<TableModelPart>(`/api/physical/table/${enc(tableId)}`, { silent: true }), kind, id);
}

const KIND_TITLE: Record<Kind, [() => string, () => string, () => string]> = {
  relation: [() => t('관계 삭제'), () => t('관계 승인'), () => t('관계 승인 취소')],
  index: [() => t('인덱스 삭제'), () => t('인덱스 승인'), () => t('인덱스 승인 취소')],
  constraint: [() => t('제약조건 삭제'), () => t('제약조건 승인'), () => t('제약조건 승인 취소')],
};

/** 관계(FK)·인덱스·제약조건 삭제 / 승인·승인 취소 (approve 가 undefined 면 삭제). seen = 화면이 본 상태 */
export async function physicalKeyGuarded(dialog: DialogApi, kind: Kind, tableId: number, id: number, seen: Snapshot, approve: boolean | undefined, after: () => unknown): Promise<boolean> {
  const [del, on, off] = KIND_TITLE[kind];
  const ok = await runWithReview({
    dialog, title: approve === undefined ? del() : approve ? on() : off(), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen, load: () => loadKey(kind, tableId, id), targetApproval: approve,
    act: async (state) => {
      if (approve === undefined) await api(`/api/physical/${kind}/${enc(id)}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' });
      else await api(`/api/physical/${kind}/${enc(id)}/approval/${approve}${baseQuery(state.modifiedAt)}`, { method: 'PATCH', conflict: 'handled' });
    },
    retryLabel: approve === undefined ? t('최신 내용으로 삭제') : approve ? t('최신 내용으로 승인') : t('최신 내용으로 승인 취소'), onClose: () => void after(),
  });
  if (ok) await after();
  return ok;
}

/** VIEW 상세의 지금 상태 */
export async function loadView(viewId: number): Promise<Snapshot> {
  const d = await api<{ isApproval: boolean | null; updated: string | null; view: { viewName: string; viewKorName: string | null; definition: string; columns: { columnName: string }[] } }>(`/api/physical/view/${enc(viewId)}`, { silent: true });
  return viewSnapshot(d);
}

export function viewSnapshot(d: { isApproval: boolean | null; updated: string | null; view: { viewName: string; viewKorName: string | null; definition: string; columns: { columnName: string }[] } }): Snapshot {
  return { modifiedAt: d.updated, approved: !!d.isApproval, fields: [
    { key: 'name', label: t('VIEW명'), value: d.view.viewName },
    { key: 'korName', label: t('한글명'), value: d.view.viewKorName ?? '' },
    { key: 'definition', label: t('정의 SQL'), value: d.view.definition },
    { key: 'columns', label: t('출력 컬럼'), value: d.view.columns.map((c) => c.columnName).join(', ') },
  ] };
}

/** 인덱스 상세의 지금 상태 */
type IndexDetailPart = { updated: string | null; index: { indexName: string; isApproval: boolean | null; columns: { columnName?: string | null }[]; condition?: string | null } };

export async function loadIndexDetail(indexId: number): Promise<Snapshot> {
  return indexSnapshot(await api<IndexDetailPart>(`/api/physical/index/${enc(indexId)}`, { silent: true }));
}

export function indexSnapshot(d: IndexDetailPart): Snapshot {
  return { modifiedAt: d.updated, approved: !!d.index.isApproval, fields: [
    { key: 'name', label: t('인덱스명'), value: d.index.indexName },
    { key: 'columns', label: t('컬럼'), value: d.index.columns.map((c) => c.columnName ?? '').join(', ') },
    { key: 'condition', label: t('조건 (WHERE)'), value: d.index.condition ?? '' },
  ] };
}

/** VIEW·인덱스 상세 화면의 삭제·승인 (경로 접두어 : /api/physical/view · /api/physical/index) */
export async function detailGuarded(dialog: DialogApi, kind: 'view' | 'index', id: number, seen: Snapshot, approve: boolean | undefined, after: () => unknown, done?: () => void): Promise<boolean> {
  const name = kind === 'view' ? 'VIEW' : t('인덱스');
  const ok = await runWithReview({
    dialog, title: approve === undefined ? t('{0} 삭제', name) : approve ? t('{0} 승인', name) : t('{0} 승인 취소', name), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen, load: () => (kind === 'view' ? loadView(id) : loadIndexDetail(id)), targetApproval: approve,
    act: async (state) => {
      if (approve === undefined) await api(`/api/physical/${kind}/${enc(id)}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' });
      else await api(`/api/physical/${kind}/${enc(id)}/approval/${approve}${baseQuery(state.modifiedAt)}`, { method: 'PATCH', conflict: 'handled' });
    },
    retryLabel: approve === undefined ? t('최신 내용으로 삭제') : approve ? t('최신 내용으로 승인') : t('최신 내용으로 승인 취소'),
    onDone: done, onClose: () => void after(),
  });
  if (ok) {
    await after();
    done?.();
  }
  return ok;
}

/** 스키마 삭제 (목록의 수정 일시) */
export async function deleteSchemaGuarded(dialog: DialogApi, schema: { id: number; schemaName: string; schemaDescription?: string | null; updated: string | null }, after: () => unknown, done: () => void): Promise<boolean> {
  const snapshot = (s: { schemaName: string; schemaDescription?: string | null; updated: string | null }): Snapshot => ({
    modifiedAt: s.updated, fields: [{ key: 'name', label: t('스키마명'), value: s.schemaName }, { key: 'description', label: t('설명'), value: s.schemaDescription ?? '' }],
  });
  const ok = await runWithReview({
    dialog, title: t('스키마 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'), seen: snapshot(schema),
    load: async () => {
      const latest = (await api<{ id: number; schemaName: string; schemaDescription?: string | null; updated: string | null }[]>('/api/schema', { silent: true })).find((s) => s.id === schema.id);
      if (!latest) throw notFound();
      return snapshot(latest);
    },
    act: async (state) => { await api(`/api/schema/${enc(schema.id)}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
    retryLabel: t('최신 내용으로 삭제'), onDone: done, onClose: () => void after(),
  });
  if (ok) {
    await after();
    done();
  }
  return ok;
}
