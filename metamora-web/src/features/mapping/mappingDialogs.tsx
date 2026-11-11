import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel } from '../column/terms';
import { useSchemas } from '../schema/schemas';
import type { ColumnCandidate, MappingAttribute, MappingDetail, MappingRow, TableCandidate } from './model';

const CANDIDATE_PAGE_SIZE = 10;

/**
 * 엔터티 ↔ 테이블 매핑 등록·변경 : 아직 매핑하지 않은 물리 테이블(현재 프로젝트)을 스키마·이름으로 찾아 고른다.
 * 후보는 서버에서 페이징한다. 1:1 과 속성 매핑이 있는 매핑의 테이블 변경 차단은 서버가 검사하고, 오류는 공통 알림으로 보여 준다.
 */
export function EntityMappingForm({ row, onSaved }: { row: MappingRow; onSaved: () => void }) {
  const dialog = useDialog();
  const { schemas } = useSchemas();
  const [schemaId, setSchemaId] = useState('');
  const [input, setInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<TableCandidate | null>(null);

  const candidates = useQuery({
    queryKey: ['mapping-table-candidates', schemaId, keyword, page],
    queryFn: () => {
      const query = new URLSearchParams({ page: String(page), size: String(CANDIDATE_PAGE_SIZE), sort: 'tableName,asc', searchQuery: keyword });
      if (schemaId) query.set('schemaId', schemaId);
      return api<{ data: TableCandidate[]; recordsTotal: number }>(`/api/modelMapping/table/data?${query}`, { silent: true });
    },
    placeholderData: (prev) => prev,
    gcTime: 0,
  });
  const total = candidates.data?.recordsTotal ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / CANDIDATE_PAGE_SIZE));

  const search = () => {
    setPage(0);
    setKeyword(input.trim());
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!selected) return dialog.alert(t('매핑할 물리 테이블을 선택해주세요.'));
    dialog.confirm(t('{0} 엔터티를 {1} 테이블에 매핑하시겠습니까?', row.entityName, selected.tableName), async () => {
      await api('/api/modelMapping/entity', { method: 'POST', body: { id: row.mappingId, entityId: row.entityId, tableId: selected.tableId } });
      dialog.alert(t('정상적으로 저장되었습니다.'), onSaved);
    });
  };

  return (
    <form className="edit-form mapping-form" onSubmit={onSubmit}>
      <dl className="dict-detail">
        <dt>{t('논리 모델')}</dt><dd>{row.modelName}</dd>
        <dt>{t('논리 엔터티')}</dt><dd>{row.entityName}</dd>
        <dt>{t('현재 물리 테이블')}</dt>
        <dd className={row.tableName ? 'gh-mono' : 'gh-muted'}>{row.tableName ? `${row.schemaName}.${row.tableName}` : t('미매핑')}</dd>
      </dl>
      {row.mappingId != null && <p className="form-help">{t('속성 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 속성 매핑을 먼저 해제해주세요.')}</p>}

      <div className="form-group">
        <label htmlFor="mappingTableKeyword">{t('물리 테이블 검색')}</label>
        <div className="mapping-search">
          <select className="form-control" aria-label={t('물리 스키마')} value={schemaId} onChange={(e) => { setSchemaId(e.target.value); setPage(0); }}>
            <option value="">{t('전체 스키마')}</option>
            {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
          </select>
          <input type="text" className="form-control" id="mappingTableKeyword" maxLength={100} placeholder={t('테이블명 또는 한글명')} value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } }} />
          <button type="button" className="btn" onClick={search}>{t('검색')}</button>
        </div>
      </div>

      <p className="gh-muted">{t('아직 매핑하지 않은 테이블 {0}개', total)}</p>
      {candidates.isError ? (
        <p role="alert">{t('후보 테이블을 불러오지 못했습니다.')}</p>
      ) : !candidates.data ? (
        <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>
      ) : candidates.data.data.length === 0 ? (
        <p className="gh-muted">{keyword || schemaId ? t('조건에 맞는 후보 테이블이 없습니다.') : t('매핑할 수 있는 테이블이 없습니다. 모든 테이블이 이미 매핑되어 있습니다.')}</p>
      ) : (
        <ul className="mapping-candidates" role="radiogroup" aria-label={t('후보 테이블')}>
          {candidates.data.data.map((table) => (
            <li key={table.tableId}>
              <label>
                <input type="radio" name="mappingTable" checked={selected?.tableId === table.tableId} onChange={() => setSelected(table)} />
                <span className="gh-mono">{table.schemaName}.{table.tableName}</span>
                {table.tableKorName && <span className="gh-muted">{table.tableKorName}</span>}
                {!table.isApproval && <Label variant="attention" dot>{t('승인 대기')}</Label>}
              </label>
            </li>
          ))}
        </ul>
      )}
      {pageCount > 1 && (
        <div className="mapping-pager">
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('이전')}</button>
          <span className="gh-muted">{page + 1} / {pageCount}</span>
          <button type="button" className="btn btn-sm" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)}>{t('다음')}</button>
        </div>
      )}

      <p className="form-help">{selected ? t('선택한 테이블 : {0}', `${selected.schemaName}.${selected.tableName}`) : t('매핑할 물리 테이블을 선택해주세요.')}</p>
      <button type="submit" className="btn btn-primary" disabled={!selected}>{t('매핑 저장')}</button>
    </form>
  );
}

const includes = (value: string | null | undefined, keyword: string) => (value ?? '').toLowerCase().includes(keyword);

/**
 * 매핑 상세 : 엔터티의 속성 전체와 매핑된 컬럼, 양쪽 표준 용어. 변경 권한이 있으면 속성마다 컬럼을 골라 매핑·변경·해제한다.
 * 컬럼 후보는 매핑된 테이블의 컬럼 중 아직 속성에 연결하지 않은 것뿐이다 (다른 속성에 매핑된 컬럼은 고를 수 없다 — 서버도 1:1 을 검사한다).
 * 표준 용어가 서로 달라도 매핑을 막거나 풀지 않는다.
 */
export function AttributeMappingDetail({ mappingId, canEdit, controls, onChanged, onQuality }: {
  mappingId: number;
  canEdit: boolean;
  controls: DialogControls;
  /** 속성 매핑이 바뀐 뒤 (목록·현황 다시 조회) */
  onChanged: () => void;
  /** [정합성 비교 보기] */
  onQuality?: (mapping: MappingRow) => void;
}) {
  const dialog = useDialog();
  const detail = useQuery({
    queryKey: ['mapping-detail', mappingId],
    queryFn: () => api<MappingDetail>(`/api/modelMapping/entity/${mappingId}`, { silent: true }),
    gcTime: 0,
  });
  // 속성 ID → 고른 컬럼 ID (저장 전)
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [keyword, setKeyword] = useState('');

  if (detail.isError) return <p role="alert">{t('매핑 정보를 불러오지 못했습니다.')} {detail.error.message}</p>;
  if (!detail.data) return <p role="status">{t('불러오는 중입니다.')}</p>;

  const { mapping, attributes, unmappedColumns } = detail.data;
  const mappedCount = attributes.filter((a) => a.mappingId != null).length;
  const filter = keyword.trim().toLowerCase();
  const visibleCandidates = filter
    ? unmappedColumns.filter((c) => includes(c.columnName, filter) || includes(c.columnDesc, filter) || includes(c.termName, filter))
    : unmappedColumns;

  const changed = () => {
    setDrafts({});
    void detail.refetch();
    onChanged();
  };

  const save = async (attribute: MappingAttribute) => {
    const columnId = Number(drafts[attribute.attributeId]);
    await api('/api/modelMapping/attribute', { method: 'POST', body: { id: attribute.mappingId, mappingId, attributeId: attribute.attributeId, columnId } });
    changed();
  };

  const remove = (attribute: MappingAttribute) => {
    dialog.confirm(t('{0} 속성의 컬럼 매핑을 해제하시겠습니까? 속성과 컬럼은 삭제되지 않습니다.', attribute.attributeName), async () => {
      await api(`/api/modelMapping/attribute/${attribute.mappingId}`, { method: 'DELETE' });
      changed();
    });
  };

  const headers = ['#', t('논리 속성'), t('논리 표준 용어'), t('물리 컬럼'), t('물리 표준 용어'), t('매핑 상태'), ...(canEdit ? [t('관리')] : [])];
  // 컬럼 폭 고정 : 고른 컬럼·검색어에 따라 표가 움직이지 않는다
  const widths = canEdit ? ['5%', '16%', '15%', '23%', '15%', '14%', '12%'] : ['5%', '21%', '19%', '19%', '19%', '17%'];
  const term = (name: string | null) => (name ? termLabel({ commonStandardTermName: name }) : <Muted>-</Muted>);

  return (
    <div className="mapping-detail">
      <div className="dict-detail-status">
        <strong>{mapping.entityName}</strong>
        <span aria-hidden>↔</span>
        <Mono>{mapping.schemaName}.{mapping.tableName}</Mono>
        <Label variant={mappedCount === attributes.length && attributes.length > 0 ? 'success' : 'attention'}>{t('속성 {0} / {1} 매핑', mappedCount, attributes.length)}</Label>
        <span className="gh-muted">{mapping.modelName}</span>
      </div>

      {canEdit && (
        <div className="mapping-search">
          <input type="text" className="form-control" aria-label={t('물리 컬럼 검색')} maxLength={100} placeholder={t('물리 컬럼 검색 (컬럼명·설명·표준 용어)')}
            value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          <span className="gh-muted">{t('매핑하지 않은 컬럼 {0}개 중 {1}개 표시', unmappedColumns.length, visibleCandidates.length)}</span>
        </div>
      )}

      {attributes.length === 0 ? (
        <p className="gh-muted">{t('등록된 속성이 없습니다.')}</p>
      ) : (
        <div className="dict-detail-columns">
          <div className="table-wrap">
            <table className="mapping-fixed-table">
              <colgroup>{widths.map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
              <thead><tr>{headers.map((h) => <th key={h}>{h}</th>)}</tr></thead>
              <tbody>
                {attributes.map((a, index) => {
                  const current = a.columnId != null ? String(a.columnId) : '';
                  const draft = drafts[a.attributeId] ?? current;
                  // 다른 속성에서 고른(저장 전) 컬럼은 다시 고를 수 없다
                  const taken = new Set(Object.entries(drafts).filter(([id]) => Number(id) !== a.attributeId).map(([, value]) => value));
                  const options = visibleCandidates.filter((c) => !taken.has(String(c.columnId)));
                  const picked: ColumnCandidate | undefined = unmappedColumns.find((c) => String(c.columnId) === draft);
                  const columnTermName = draft === current ? a.columnTermName : picked?.termName ?? null;
                  const columnTermId = draft === current ? a.columnTermId : picked?.termId ?? null;
                  const termDiffers = draft !== '' && (a.attributeTermId != null || columnTermId != null) && a.attributeTermId !== columnTermId;
                  return (
                    <tr key={a.attributeId}>
                      <td className="text-center">{index + 1}</td>
                      <td>{a.attributeName}</td>
                      <td>{term(a.attributeTermName)}</td>
                      <td>
                        {canEdit ? (
                          <select className="form-control mapping-column-select" aria-label={t('{0} 속성의 물리 컬럼', a.attributeName)} value={draft}
                            onChange={(e) => setDrafts({ ...drafts, [a.attributeId]: e.target.value })}>
                            <option value="">{t('컬럼 선택')}</option>
                            {a.columnId != null && <option value={a.columnId}>{a.columnName}</option>}
                            {/* 검색어로 걸러져도 고른 컬럼은 남긴다 */}
                            {picked && !options.includes(picked) && <option value={picked.columnId}>{picked.columnName}</option>}
                            {options.map((c) => <option key={c.columnId} value={c.columnId}>{c.columnName}{c.columnDesc ? ` · ${c.columnDesc}` : ''}</option>)}
                          </select>
                        ) : a.columnName ? <Mono>{a.columnName}</Mono> : <Muted>-</Muted>}
                      </td>
                      <td>{term(columnTermName)}</td>
                      <td>
                        {a.mappingId != null ? <Label variant="success" dot>{t('매핑 완료')}</Label> : <Label variant="attention" dot>{t('미매핑')}</Label>}
                        {termDiffers && <> <Label variant="danger" title={t('표준 용어가 달라도 매핑은 유지됩니다. 정합성 비교에서 확인할 수 있습니다.')}>{t('표준 용어 다름')}</Label></>}
                      </td>
                      {canEdit && (
                        <td>
                          <div className="logical-actions">
                            <button type="button" className="btn btn-sm btn-primary" disabled={draft === '' || draft === current} onClick={() => void save(a)}>
                              {a.mappingId != null ? t('변경') : t('매핑')}
                            </button>
                            {a.mappingId != null && <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(a)}>{t('해제')}</button>}
                          </div>
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <h4 className="mapping-subtitle">{t('매핑하지 않은 물리 컬럼 ({0})', unmappedColumns.length)}</h4>
      {unmappedColumns.length === 0 ? (
        <p className="gh-muted">{t('테이블의 모든 컬럼이 속성에 매핑되어 있습니다.')}</p>
      ) : (
        <p className="mapping-chips">
          {unmappedColumns.map((c) => <Label key={c.columnId} title={[c.columnDesc, c.termName].filter(Boolean).join(' · ') || undefined}><span className="gh-mono">{c.columnName}</span></Label>)}
        </p>
      )}

      <div className="gh-dialog-actions">
        {onQuality && <button type="button" className="btn" onClick={() => { controls.close(); onQuality(mapping); }}>{t('정합성 비교 보기')}</button>}
      </div>
    </div>
  );
}
