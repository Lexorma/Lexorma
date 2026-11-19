import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PAGE_SIZES, Paging } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { EntityForm, useEntityDetail } from './entityDetail';
import { relationLabel, RelationshipForm } from './relationshipForm';
import { PairStatusLabel, RelationshipPairsEditor } from './relationshipPairs';
import {
  cardinalityText, manageQueryKey, participation, useLogicalModels,
  type GridPage, type LogicalEntity, type LogicalEntityRow, type LogicalModel, type LogicalModelSummary, type LogicalRelationship,
} from './model';
import '../../styles/logical.css';

/**
 * 논리 모델 관리 : 모델 선택·등록·수정·삭제, 모델의 엔터티(상세 대화상자에서 속성·식별자)와 관계 관리.
 * 조회는 VIEWER, 변경 버튼은 USER 이상(logical-model-edit), 승인은 승인 권한자 — 서버 API 가 같은 기준으로 다시 검사한다.
 * M-6 후속 3차 : 전체 그래프(/erd)를 받지 않는다 — 상단 수는 요약 API, 엔터티·관계 목록은 서버 페이지(검색·정렬·수 집계는 서버), 엔터티는 열 때 상세를 받는다.
 */
export function LogicalModelPage() {
  const dialog = useDialog();
  const navigate = useNavigate();
  const { canView } = useAuth();
  const canEdit = canView('logical-model-edit');
  const { models, selected, select, refresh: refreshModels, isPending, isError } = useLogicalModels();
  const [, setParams] = useSearchParams();

  const erdPath = (entityId?: number) => `/logicalErd?modelId=${selected?.id}${entityId != null ? `&entityId=${entityId}` : ''}`;

  const openModelForm = (model?: LogicalModel) => {
    dialog.open({
      title: model ? t('논리 모델 수정') : t('논리 모델 등록'),
      content: ({ close }: DialogControls) => (
        <ModelForm model={model} onSaved={(saved) => {
          close();
          void refreshModels();
          select(saved.id);
        }} />
      ),
    });
  };

  const removeModel = (model: LogicalModel) => {
    dialog.confirm(t('{0} 논리 모델을 삭제하시겠습니까?', model.modelName), async () => {
      await api(`/api/logical/model/${model.id}`, { method: 'DELETE' });
      setParams({}, { replace: true });
      dialog.alert(t('정상적으로 삭제되었습니다.'), () => void refreshModels());
    });
  };

  return (
    <>
      <PageHeader category={t('논리 모델 관리')} title={t('논리 모델 관리')}
        description={t('업무 관점의 논리 모델을 관리합니다. 모델을 고른 뒤 엔터티·속성·식별자·관계를 등록하며, 물리 테이블·컬럼과는 독립입니다.')} />

      <div className="grid-header">
        <div className="search-container">
          <select className="search-column-select" aria-label={t('논리 모델')} value={selected?.id ?? ''} disabled={!models.length} onChange={(e) => select(Number(e.target.value))}>
            {!models.length && <option value="">{isPending ? t('불러오는 중입니다.') : t('논리 모델 없음')}</option>}
            {models.map((m) => <option key={m.id} value={m.id}>{m.modelName}</option>)}
          </select>
        </div>
        <div className="button-container">
          {canEdit && <button type="button" className="btn btn-primary" onClick={() => openModelForm()}>{t('모델 등록')}</button>}
          {canEdit && selected && <button type="button" className="btn" onClick={() => openModelForm(selected)}>{t('모델 수정')}</button>}
          {canEdit && selected && <ModelDeleteButton model={selected} onDelete={removeModel} />}
          {selected && canView('logical-erd') && <button type="button" className="btn" onClick={() => navigate(erdPath())}>{t('논리 ERD')}</button>}
        </div>
      </div>

      {isError && <p role="alert">{t('논리 모델 목록을 불러오지 못했습니다.')}</p>}
      {!isPending && !isError && !selected && (
        <p className="logical-empty">{canEdit ? t('등록된 논리 모델이 없습니다. [모델 등록] 으로 첫 논리 모델을 만들어주세요.') : t('등록된 논리 모델이 없습니다.')}</p>
      )}

      {/* 모델마다 새로 그린다 : 페이지·검색·정렬·탭이 처음으로 돌아가고, 앞 모델의 늦은 응답은 다른 조회 키라 섞이지 않는다 */}
      {selected && <ModelContent key={selected.id} model={selected} erdPath={erdPath} />}
    </>
  );
}

/** 모델 요약 (모델 전체 엔터티·관계 수) */
function useModelSummary(modelId: number) {
  const { user } = useAuth();
  return useQuery({
    queryKey: [...manageQueryKey(user?.projectId, modelId), 'summary'],
    queryFn: ({ signal }) => api<LogicalModelSummary>(`/api/logical/model/${modelId}/summary`, { signal, silent: true }),
  });
}

/** 엔터티가 있는 모델은 삭제할 수 없다 (서버도 막는다) : 요약의 엔터티 수로 판단한다 */
function ModelDeleteButton({ model, onDelete }: { model: LogicalModel; onDelete: (model: LogicalModel) => void }) {
  const summary = useModelSummary(model.id);
  const blocked = summary.data?.entityCount ? t('엔터티가 있는 논리 모델은 삭제할 수 없습니다. 엔터티를 먼저 삭제해주세요.') : '';
  return (
    <button type="button" className="btn btn-danger" disabled={!summary.data || !!blocked} title={blocked || undefined} onClick={() => onDelete(model)}>{t('모델 삭제')}</button>
  );
}

type SortColumn = 'entityName' | 'entityDescription' | 'isApproval';
/** 관계 정렬 (후속 3차 보완) : 없으면 서버 기본 = 관계 ID 순 */
type RelationshipSortColumn = 'relationshipName' | 'sourceEntityName' | 'targetEntityName';

function ModelContent({ model, erdPath }: { model: LogicalModel; erdPath: (entityId?: number) => string }) {
  const dialog = useDialog();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, canView, approvalAvailable } = useAuth();
  const canEdit = canView('logical-model-edit');
  const openEntityDetail = useEntityDetail();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<'entities' | 'relationships'>('entities');
  const summary = useModelSummary(model.id);
  const key = manageQueryKey(user?.projectId, model.id);

  // 엔터티 목록 : 검색어는 [조회]·Enter 로 적용한다 (입력 중에는 다시 조회하지 않는다)
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<{ column: SortColumn; desc: boolean }>({ column: 'entityName', desc: false });
  const [entityPage, setEntityPage] = useState({ page: 0, size: 10 });
  const entityQuery = new URLSearchParams({ page: String(entityPage.page), size: String(entityPage.size), sort: `${sort.column},${sort.desc ? 'desc' : 'asc'}`, searchQuery: search });
  const entities = useQuery({
    queryKey: [...key, 'entities', entityQuery.toString()],
    queryFn: ({ signal }) => api<GridPage<LogicalEntityRow>>(`/api/logical/model/${model.id}/entity/data?${entityQuery}`, { signal, silent: true }),
    placeholderData: (previous) => previous,
  });

  // 관계 목록 (후속 3차 보완) : 검색어(관계명·출발·도착 엔터티명)·식별 여부·정렬은 서버가 처리한다. 엔터티 탭과 상태를 따로 둔다
  const [relationshipSearchInput, setRelationshipSearchInput] = useState('');
  const [relationshipSearch, setRelationshipSearch] = useState('');
  const [identifying, setIdentifying] = useState<'' | 'true' | 'false'>('');
  const [relationshipSort, setRelationshipSort] = useState<{ column: RelationshipSortColumn; desc: boolean } | null>(null);
  const [relationshipPage, setRelationshipPage] = useState({ page: 0, size: 10 });
  const relationshipQuery = new URLSearchParams({ page: String(relationshipPage.page), size: String(relationshipPage.size), searchQuery: relationshipSearch });
  if (identifying) relationshipQuery.set('isIdentifying', identifying);
  if (relationshipSort) relationshipQuery.set('sort', `${relationshipSort.column},${relationshipSort.desc ? 'desc' : 'asc'}`);
  const relationshipFiltered = !!relationshipSearch || !!identifying;
  const relationships = useQuery({
    // 조건마다 다른 키 : 늦게 온 앞 조건의 응답이 지금 목록을 덮지 않는다 (요청은 signal 로 취소)
    queryKey: [...key, 'relationships', relationshipQuery.toString()],
    queryFn: ({ signal }) => api<GridPage<LogicalRelationship>>(`/api/logical/model/${model.id}/relationship/data?${relationshipQuery}`, { signal, silent: true }),
    placeholderData: (previous) => previous,
    enabled: tab === 'relationships',
  });

  // 지금 페이지가 비었는데 앞 페이지가 있으면(마지막 행 삭제·검색 결과 감소) 마지막 페이지로 옮긴다
  useLastPage(entities.data, entityPage, setEntityPage);
  useLastPage(relationships.data, relationshipPage, setRelationshipPage);

  // 변경 뒤 : 이 모델의 요약·목록만 다시 받는다 (엔터티·관계 수는 서버가 다시 센다 — 화면이 더하거나 빼지 않는다)
  const refresh = () => void queryClient.invalidateQueries({ queryKey: key });
  const openEntity = (entityId: number) => openEntityDetail(entityId, { onChanged: refresh, onErd: canView('logical-erd') ? () => navigate(erdPath(entityId)) : undefined });

  // 논리 ERD·매핑 관리에서 넘어온 경우 (?modelId=&entityId=) : 목록 페이지와 무관하게 그 엔터티의 상세를 한 번 연다 (이 모델의 엔터티일 때만 — 예전과 같다)
  const linkedEntity = params.get('entityId');
  useEffect(() => {
    if (!linkedEntity) return;
    setParams({ modelId: String(model.id) }, { replace: true });
    api<LogicalEntity>(`/api/logical/entity/${linkedEntity}`, { silent: true })
      .then((entity) => { if (entity.modelId === model.id) openEntity(entity.id); })
      .catch(() => undefined); // 없거나 다른 프로젝트의 엔터티 : 예전처럼 열지 않는다
  }, [linkedEntity]);

  const applySearch = (value: string) => {
    setSearch(value.trim());
    setEntityPage((p) => ({ ...p, page: 0 }));
  };
  const toggleSort = (column: SortColumn) => {
    setSort((s) => ({ column, desc: s.column === column ? !s.desc : false }));
    setEntityPage((p) => ({ ...p, page: 0 }));
  };
  const applyRelationshipSearch = (value: string) => {
    setRelationshipSearch(value.trim());
    setRelationshipPage((p) => ({ ...p, page: 0 }));
  };
  const changeIdentifying = (value: '' | 'true' | 'false') => {
    setIdentifying(value);
    setRelationshipPage((p) => ({ ...p, page: 0 }));
  };
  const toggleRelationshipSort = (column: RelationshipSortColumn) => {
    setRelationshipSort((s) => ({ column, desc: s?.column === column ? !s.desc : false }));
    setRelationshipPage((p) => ({ ...p, page: 0 }));
  };

  const openEntityForm = () => {
    dialog.open({
      title: t('엔터티 등록'),
      content: ({ close }: DialogControls) => (
        <EntityForm modelId={model.id} onSaved={(entityId) => {
          close();
          refresh();
          openEntity(entityId); // 이어서 속성·식별자를 등록한다
        }} />
      ),
    });
  };

  // 관계 속성 짝 (M-7 보강 3차) : 편집 권한이 있고 승인 전인 관계만 바꿀 수 있다 (승인된 관계는 보기만)
  const openPairs = (relationship: LogicalRelationship) => {
    dialog.open({
      title: t('관계 속성 짝 — {0}', relationLabel(relationship)),
      className: 'dialog-wide',
      content: () => <RelationshipPairsEditor relationshipId={relationship.id} readOnly={!canEdit || !!relationship.isApproval} onSaved={() => refresh()} />,
    });
  };
  const openRelationshipForm = (relationship?: LogicalRelationship) => {
    if (summary.data?.entityCount === 0) return dialog.alert(t('엔터티를 먼저 등록해주세요.'));
    dialog.open({
      title: relationship ? t('관계 수정') : t('관계 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <RelationshipForm modelId={model.id} relationship={relationship} onSaved={() => { close(); refresh(); }} />
      ),
    });
  };

  const removeRelationship = (relationship: LogicalRelationship) => {
    dialog.confirm(t('{0} 관계를 삭제하시겠습니까?', relationLabel(relationship)), async () => {
      await api(`/api/logical/relationship/${relationship.id}`, { method: 'DELETE' });
      refresh();
    });
  };

  const toggleRelationshipApproval = (relationship: LogicalRelationship) => {
    const approved = !!relationship.isApproval;
    dialog.confirm(approved ? t('이 관계를 승인취소하시겠습니까?') : t('이 관계를 승인하시겠습니까?'), async () => {
      await api(`/api/logical/relationship/${relationship.id}/approval/${!approved}`, { method: 'PATCH' });
      refresh();
    });
  };

  const entityHeaders: [string, SortColumn | null][] = [[t('엔터티명'), 'entityName'], [t('설명'), 'entityDescription'], [t('속성 수'), null], [t('관계 수'), null], [t('승인 상태'), 'isApproval'], [t('관리'), null]];
  const relationshipHeaders: [string, RelationshipSortColumn | null][] = [[t('관계명'), 'relationshipName'], [t('출발 엔터티'), 'sourceEntityName'], [t('도착 엔터티'), 'targetEntityName'],
    [t('식별 관계'), null], [t('카디널리티'), null], [t('속성 짝'), null], [t('승인 상태'), null], ...(canEdit || approvalAvailable ? [[t('관리'), null] as [string, null]] : [])];
  const entityRows = entities.data?.data ?? [];
  const relationshipRows = relationships.data?.data ?? [];
  const count = (value: number | undefined) => (value == null ? '…' : value);

  return (
    <>
      {model.modelDescription && <p className="page-desc">{model.modelDescription}</p>}
      {summary.isError && <p role="alert">{t('논리 모델 요약을 불러오지 못했습니다.')}</p>}
      <ul className="nav nav-tabs">
        <li className="nav-item">
          <a href="#logical-entities" className={`nav-link${tab === 'entities' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('entities'); }}>{t('엔터티 ({0})', count(summary.data?.entityCount))}</a>
        </li>
        <li className="nav-item">
          <a href="#logical-relationships" className={`nav-link${tab === 'relationships' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('relationships'); }}>{t('관계 ({0})', count(summary.data?.relationshipCount))}</a>
        </li>
      </ul>

      <div className="grid-header mt-3">
        {tab === 'entities' && (
          <form className="search-container" role="search" onSubmit={(e) => { e.preventDefault(); applySearch(searchInput); }}>
            <input type="search" className="search-input search-input-text" aria-label={t('엔터티 검색')} placeholder={t('엔터티명 / 설명')} maxLength={100}
              value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
            <button type="submit" className="grd-select">{t('조회')}</button>
            {search && <button type="button" className="btn" onClick={() => { setSearchInput(''); applySearch(''); }}>{t('초기화')}</button>}
          </form>
        )}
        {tab === 'relationships' && (
          <form className="search-container logical-relationship-search" role="search" onSubmit={(e) => { e.preventDefault(); applyRelationshipSearch(relationshipSearchInput); }}>
            <select className="search-column-select" aria-label={t('식별 여부')} value={identifying} onChange={(e) => changeIdentifying(e.target.value as '' | 'true' | 'false')}>
              <option value="">{t('전체')}</option>
              <option value="true">{t('식별 관계')}</option>
              <option value="false">{t('비식별 관계')}</option>
            </select>
            <input type="search" className="search-input search-input-text" aria-label={t('관계 검색')} placeholder={t('관계명 / 출발·도착 엔터티명')} maxLength={100}
              value={relationshipSearchInput} onChange={(e) => setRelationshipSearchInput(e.target.value)} />
            <button type="submit" className="grd-select">{t('조회')}</button>
            {relationshipFiltered && (
              <button type="button" className="btn" onClick={() => { setRelationshipSearchInput(''); setIdentifying(''); applyRelationshipSearch(''); }}>{t('초기화')}</button>
            )}
          </form>
        )}
        <div className="button-container">
          {canEdit && tab === 'entities' && <button type="button" className="grd-add btn btn-primary" onClick={openEntityForm}>{t('엔터티 등록')}</button>}
          {canEdit && tab === 'relationships' && <button type="button" className="grd-add btn btn-primary" onClick={() => openRelationshipForm()}>{t('관계 등록')}</button>}
        </div>
      </div>

      <div className="dt-container">
        {tab === 'entities' ? (
          <>
            <ListStatus size={entityPage.size} onSize={(size) => setEntityPage({ page: 0, size })}
              text={entities.data ? (search ? t('검색 결과 {0}개 (모델 전체 {1}개)', entities.data.recordsTotal, count(summary.data?.entityCount)) : t('엔터티 {0}개', entities.data.recordsTotal)) : ''}
              loading={entities.isFetching} />
            <div className="dt-layout-row dt-layout-table">
              <div className="dt-layout-cell dt-layout-full">
                <table id="logicalEntityGrid" className="table table-striped table-bordered dataTable grid-fixed" style={{ minWidth: ENTITY_GRID_MIN_WIDTH }}>
                  <colgroup>{ENTITY_WIDTHS.map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
                  <thead>
                    <tr>
                      {entityHeaders.map(([label, column]) => (
                        <SortHeader key={label} label={label} ordering={column && sort.column === column ? (sort.desc ? 'desc' : 'asc') : null} onSort={column ? () => toggleSort(column) : undefined} />
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {entityRows.map((entity) => (
                      <tr key={entity.id}>
                        <td><button type="button" className="logical-link" onClick={() => openEntity(entity.id)}>{entity.entityName}</button></td>
                        <td>{entity.entityDescription || '-'}</td>
                        <td>{entity.attributeCount}</td>
                        <td>{entity.relationshipCount}</td>
                        <td><ApprovalLabel value={entity.isApproval} /></td>
                        <td>
                          <div className="logical-actions">
                            <button type="button" className="btn btn-sm" onClick={() => openEntity(entity.id)}>{t('상세')}</button>
                            {canView('logical-erd') && <button type="button" className="btn btn-sm" onClick={() => navigate(erdPath(entity.id))}>{t('ERD')}</button>}
                          </div>
                        </td>
                      </tr>
                    ))}
                    {entityRows.length === 0 && (
                      <tr><td colSpan={entityHeaders.length} className="dt-empty" role={entities.isError ? 'alert' : undefined}>
                        {entities.isError ? t('엔터티 목록을 불러오지 못했습니다.') : entities.isPending ? t('불러오는 중입니다.') : search ? t('검색된 데이터가 없습니다.') : t('등록된 엔터티가 없습니다.')}
                      </td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
            <PageNav data={entities.data} state={entityPage} onPage={(page) => setEntityPage((p) => ({ ...p, page }))} />
          </>
        ) : (
          <>
            <ListStatus size={relationshipPage.size} onSize={(size) => setRelationshipPage({ page: 0, size })}
              text={relationships.data ? (relationshipFiltered ? t('검색 결과 {0}개 (모델 전체 {1}개)', relationships.data.recordsTotal, count(summary.data?.relationshipCount)) : t('관계 {0}개', relationships.data.recordsTotal)) : ''}
              loading={relationships.isFetching} />
            <div className="dt-layout-row dt-layout-table">
              <div className="dt-layout-cell dt-layout-full">
                <table id="logicalRelationshipGrid" className="table table-striped table-bordered dataTable grid-fixed"
                  style={{ minWidth: canEdit || approvalAvailable ? RELATIONSHIP_GRID_MIN_WIDTH_MANAGED : RELATIONSHIP_GRID_MIN_WIDTH }}>
                  <colgroup>{(canEdit || approvalAvailable ? RELATIONSHIP_WIDTHS_MANAGED : RELATIONSHIP_WIDTHS).map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
                  <thead>
                    <tr>
                      {relationshipHeaders.map(([label, column]) => (
                        <SortHeader key={label} label={label} ordering={column && relationshipSort?.column === column ? (relationshipSort.desc ? 'desc' : 'asc') : null}
                          onSort={column ? () => toggleRelationshipSort(column) : undefined} />
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {relationshipRows.map((r) => (
                      <tr key={r.id}>
                        <td>{r.relationshipName || '-'}</td>
                        <td>{r.sourceEntityName} <span className="gh-muted gh-mono">({participation(r.sourceMin, r.sourceMax)})</span></td>
                        <td>{r.targetEntityName} <span className="gh-muted gh-mono">({participation(r.targetMin, r.targetMax)})</span></td>
                        <td>{r.isIdentifying ? <Label variant="accent">{t('식별')}</Label> : <span className="gh-muted">{t('비식별')}</span>}</td>
                        <td className="gh-mono" title={cardinalityText(r)}>{r.cardinality}</td>
                        <td>
                          <button type="button" className="logical-link" title={t('속성 짝 보기·편집')} onClick={() => openPairs(r)}>
                            <PairStatusLabel state={r.attributePairs} />{r.attributePairs && r.attributePairs.pairs.length > 0 && <span className="gh-muted"> {r.attributePairs.pairs.length}</span>}
                          </button>
                        </td>
                        <td><ApprovalLabel value={r.isApproval} /></td>
                        {(canEdit || approvalAvailable) && (
                          <td>
                            <div className="logical-actions">
                              {canEdit && (
                                <>
                                  <button type="button" className="btn btn-sm" disabled={!!r.isApproval} title={r.isApproval ? t('승인된 관계는 승인취소 후에 변경할 수 있습니다.') : undefined} onClick={() => openRelationshipForm(r)}>{t('수정')}</button>
                                  <button type="button" className="btn btn-sm btn-danger" disabled={!!r.isApproval} title={r.isApproval ? t('승인된 관계는 승인취소 후에 변경할 수 있습니다.') : undefined} onClick={() => removeRelationship(r)}>{t('삭제')}</button>
                                </>
                              )}
                              {approvalAvailable && <button type="button" className="btn btn-sm" onClick={() => toggleRelationshipApproval(r)}>{r.isApproval ? t('승인취소') : tc('버튼', '승인')}</button>}
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                    {relationshipRows.length === 0 && (
                      <tr><td colSpan={relationshipHeaders.length} className="dt-empty" role={relationships.isError ? 'alert' : undefined}>
                        {relationships.isError ? t('관계 목록을 불러오지 못했습니다.') : relationships.isPending ? t('불러오는 중입니다.') : relationshipFiltered ? t('검색된 데이터가 없습니다.') : t('등록된 관계가 없습니다.')}
                      </td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
            <PageNav data={relationships.data} state={relationshipPage} onPage={(page) => setRelationshipPage((p) => ({ ...p, page }))} />
          </>
        )}
      </div>
    </>
  );
}

interface PageState {
  page: number;
  size: number;
}

/** 빈 페이지인데 앞에 데이터가 있으면 마지막 페이지로 (삭제·검색으로 줄어든 경우) */
function useLastPage(data: GridPage<unknown> | undefined, state: PageState, set: (update: (p: PageState) => PageState) => void) {
  useEffect(() => {
    if (!data || data.data.length > 0 || state.page === 0) return;
    const last = Math.max(0, Math.ceil(data.recordsTotal / state.size) - 1);
    if (last !== state.page) set((p) => ({ ...p, page: last }));
  }, [data, state.page, state.size]);
}

/** 목록 위 줄 : 페이지 크기(공용 그리드와 같은 선택지)와 건수 */
function ListStatus({ size, onSize, text, loading }: { size: number; onSize: (size: number) => void; text: string; loading: boolean }) {
  return (
    <div className="dt-layout-row">
      <div className="dt-layout-cell dt-layout-start">
        <div className="dt-length">
          <label>
            {t('페이지당')}{' '}
            <select className="dt-input" value={size} onChange={(e) => onSize(Number(e.target.value))}>
              {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>{' '}
            {t('행 표시')}
          </label>
          <span className="gh-muted logical-list-count" role="status">{text}{loading ? ` · ${t('불러오는 중입니다.')}` : ''}</span>
        </div>
      </div>
    </div>
  );
}

function PageNav({ data, state, onPage }: { data: GridPage<unknown> | undefined; state: PageState; onPage: (page: number) => void }) {
  const pageCount = Math.max(1, Math.ceil((data?.recordsTotal ?? 0) / state.size));
  return (
    <div className="dt-layout-row">
      <div className="dt-layout-cell dt-layout-end">
        <Paging page={Math.min(state.page, pageCount - 1)} pageCount={pageCount} onChange={onPage} />
      </div>
    </div>
  );
}

/**
 * 표의 최소 폭(px) — 공용 그리드의 minWidth 와 같은 방식 (후속 3차 보완). 고정 배치 표의 머리글은 줄바꿈하지 않아(layout.css) 좁은 화면에서 열이 수십 px 로 줄면
 * 머리글이 옆 칸과 겹쳤다. 이 폭 아래에서는 줄이지 않고 표 영역(.dt-layout-table, overflow-x auto)만 가로 스크롤한다 — 문서 전체는 스크롤하지 않는다
 */
const ENTITY_GRID_MIN_WIDTH = 900;
const RELATIONSHIP_GRID_MIN_WIDTH = 960;
const RELATIONSHIP_GRID_MIN_WIDTH_MANAGED = 1120;

/** 정렬할 수 있는 머리글 (공용 그리드와 같은 표시·aria-sort). onSort 가 없으면 정렬하지 않는 열 */
function SortHeader({ label, ordering, onSort }: { label: string; ordering: 'asc' | 'desc' | null; onSort?: () => void }) {
  if (!onSort) return <th className="text-start no-sort">{label}</th>;
  return (
    <th className={`text-start dt-orderable-asc dt-orderable-desc${ordering ? ` dt-ordering-${ordering}` : ''}`} onClick={onSort}
      aria-sort={ordering === 'asc' ? 'ascending' : ordering === 'desc' ? 'descending' : undefined}>
      <div className="dt-column-header"><span className="dt-column-title">{label}</span><span className="dt-column-order" /></div>
    </th>
  );
}

/**
 * 목록 컬럼 폭 (고정) : 엔터티 6열, 관계 7열 + 관리. 머리글은 줄바꿈하므로(logical.css) 한 단어(영어 'Relationships' 등)가 최소 폭에서 들어가는 비율로 잡는다 (후속 3차 보완)
 */
const ENTITY_WIDTHS = ['22%', '30%', '11%', '12%', '12%', '13%'];
const RELATIONSHIP_WIDTHS = ['16%', '17%', '17%', '12%', '10%', '14%', '14%'];
const RELATIONSHIP_WIDTHS_MANAGED = ['13%', '14%', '14%', '11%', '9%', '11%', '11%', '17%'];


function ApprovalLabel({ value }: { value: boolean | null }) {
  return value ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>;
}

function ModelForm({ model, onSaved }: { model?: LogicalModel; onSaved: (model: LogicalModel) => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(model?.modelName ?? '');
  const [description, setDescription] = useState(model?.modelDescription ?? '');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('모델명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(model ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      // 이름 중복(프로젝트 안, 대소문자 무시)은 서버에서 검증한다
      const saved = await api<LogicalModel>('/api/logical/model', { method: 'POST', body: { id: model?.id ?? null, modelName: name.trim(), modelDescription: description.trim() || null, modifiedAt: model?.modifiedAt ?? null } });
      dialog.alert(model ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'), () => onSaved(saved));
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="modelName">{t('모델명')}</label>
        <input type="text" className="form-control" id="modelName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="modelDescription">{t('설명')}</label>
        <textarea className="form-control" id="modelDescription" maxLength={1000} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}
