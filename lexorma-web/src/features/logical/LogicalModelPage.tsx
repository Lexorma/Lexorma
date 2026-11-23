import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { Label, SkeletonRows } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { EntityForm, useEntityDetail } from './entityDetail';
import { AreaForm, ClassifyForm, SubjectAreaPanel, useAreaRemoval, type ClassifyTarget } from './SubjectAreaPanel';
import { ALL_AREAS, applyAreaFilter, areaMap, areaPath, MAX_AREA_DEPTH, subjectAreaQueryKey, useSubjectAreas, type AreaFilter, type SubjectArea } from './subjectArea';
import { relationLabel, RelationshipForm } from './relationshipForm';
import { PairStatusLabel, RelationshipPairsEditor } from './relationshipPairs';
import {
  cardinalityText, manageQueryKey, participation, useLogicalModels,
  type GridPage, type LogicalEntity, type LogicalEntityRow, type LogicalModel, type LogicalModelSummary, type LogicalRelationship,
} from './model';
import { ApprovalLabel, ListStatus, PageNav, SortHeader, useLastPage } from './listParts';
import { SubtypeGroupTab } from './SubtypePanel';
import '../../styles/logical.css';

/**
 * 논리 모델 관리 : 모델 선택·등록·수정·삭제, 모델의 엔터티(상세 대화상자에서 속성·식별자)와 관계 관리.
 * 조회는 VIEWER, 변경 버튼은 USER 이상(logical-model-edit), 승인은 승인 권한자 — 서버 API 가 같은 기준으로 다시 검사한다.
 * M-6 후속 3차 : 전체 그래프(/erd)를 받지 않는다 — 상단 수는 요약 API, 엔터티·관계 목록은 서버 페이지(검색·정렬·수 집계는 서버), 엔터티는 열 때 상세를 받는다.
 * M-6 후속 4차 : 왼쪽 주제 영역 패널(전체·미분류·계층)로 목록 범위를 고르고, 엔터티를 골라 분류를 바꾼다 (별도 메뉴 없음). 영역은 ID 로 고른다.
 * M-6 후속 5차 : 서브타입 그룹 탭 (슈퍼타입·서브타입 — 모델 전체 기준, 주제 영역 범위와 무관)
 */
export function LogicalModelPage() {
  const dialog = useDialog();
  const toast = useToast();
  const navigate = useNavigate();
  const { canView } = useAuth();
  const canEdit = canView('logical-model-edit');
  const { models, selected, select, refresh: refreshModels, isPending, isError } = useLogicalModels();
  const [, setParams] = useSearchParams();

  const erdPath = (entityId?: number, filter: AreaFilter = ALL_AREAS) => {
    const query = applyAreaFilter(new URLSearchParams({ modelId: String(selected?.id) }), filter);
    if (entityId != null) query.set('entityId', String(entityId));
    return `/logicalErd?${query}`;
  };

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
      void refreshModels();
      toast.success(t('정상적으로 삭제되었습니다.'));
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

function ModelContent({ model, erdPath }: { model: LogicalModel; erdPath: (entityId?: number, filter?: AreaFilter) => string }) {
  const dialog = useDialog();
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, canView, approvalAvailable } = useAuth();
  const canEdit = canView('logical-model-edit');
  const openEntityDetail = useEntityDetail();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<'entities' | 'relationships' | 'subtypes'>('entities');
  const summary = useModelSummary(model.id);
  const key = manageQueryKey(user?.projectId, model.id);

  // 주제 영역 (M-6 후속 4차) : 고른 범위(전체·미분류·영역)로 엔터티·관계 목록을 서버에서 거른다. 범위를 바꾸면 첫 페이지로
  const areas = useSubjectAreas(model.id);
  const areaById = areaMap(areas.data);
  const [filter, setFilterState] = useState<AreaFilter>(() => {
    const linked = params.get('subjectAreaId');
    return linked && /^\d+$/.test(linked) ? { kind: 'area', areaId: Number(linked), includeChildren: params.get('includeChildren') !== 'false' } : ALL_AREAS;
  });
  const [panelOpen, setPanelOpen] = useState(false);
  const selectedArea = filter.kind === 'area' ? areaById.get(filter.areaId) : undefined;
  const [selection, setSelection] = useState(new Map<number, ClassifyTarget>());
  const setFilter = (next: AreaFilter) => {
    setFilterState(next);
    setEntityPage((p) => ({ ...p, page: 0 }));
    setRelationshipPage((p) => ({ ...p, page: 0 }));
    setSelection(new Map());
    setPanelOpen(false);
  };
  // 고른 영역이 지워졌거나(다른 화면·다른 사용자) 이 모델의 영역이 아니면 전체로 돌아간다 — 다른 모델의 영역을 보여 주지 않는다
  useEffect(() => {
    if (filter.kind === 'area' && areas.data && !areaById.has(filter.areaId)) {
      if (params.get('subjectAreaId')) dialog.alert(t('주제 영역을 찾을 수 없어 전체 엔터티를 표시합니다.'));
      setFilter(ALL_AREAS);
    }
    if (params.has('subjectAreaId')) setParams((current) => { current.delete('subjectAreaId'); current.delete('includeChildren'); return current; }, { replace: true });
  }, [areas.data]);

  // 엔터티 목록 : 검색어는 [조회]·Enter 로 적용한다 (입력 중에는 다시 조회하지 않는다)
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<{ column: SortColumn; desc: boolean }>({ column: 'entityName', desc: false });
  const [entityPage, setEntityPage] = useState({ page: 0, size: 10 });
  const entityQuery = applyAreaFilter(new URLSearchParams({ page: String(entityPage.page), size: String(entityPage.size), sort: `${sort.column},${sort.desc ? 'desc' : 'asc'}`, searchQuery: search }), filter);
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
  const relationshipQuery = applyAreaFilter(new URLSearchParams({ page: String(relationshipPage.page), size: String(relationshipPage.size), searchQuery: relationshipSearch }), filter);
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

  // 변경 뒤 : 이 모델의 요약·목록·주제 영역 수만 다시 받는다 (엔터티·관계 수는 서버가 다시 센다 — 화면이 더하거나 빼지 않는다)
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: key });
    void queryClient.invalidateQueries({ queryKey: subjectAreaQueryKey(user?.projectId, model.id) });
  };
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
    setSelection(new Map());
  };
  const toggleSort = (column: SortColumn) => {
    setSort((s) => ({ column, desc: s.column === column ? !s.desc : false }));
    setEntityPage((p) => ({ ...p, page: 0 }));
    setSelection(new Map());
  };
  // 분류 변경은 지금 페이지에서 고른 엔터티만 (화면에 없는 검색 결과 전체를 바꾸지 않는다) : 페이지가 바뀌면 고른 것을 비운다
  useEffect(() => setSelection(new Map()), [entityPage.page, entityPage.size]);
  const toggleRow = (row: LogicalEntityRow) => setSelection((current) => {
    const next = new Map(current);
    if (next.has(row.id)) next.delete(row.id);
    else next.set(row.id, { id: row.id, name: row.entityName, subjectAreaId: row.subjectAreaId ?? null });
    return next;
  });

  const openAreaForm = (area?: SubjectArea, parentId?: number | null) => {
    if (!areas.data) return;
    const tree = areas.data;
    dialog.open({
      title: area ? t('주제 영역 수정') : t('주제 영역 등록'),
      content: ({ close }: DialogControls) => (
        <AreaForm modelId={model.id} tree={tree} area={area} parentId={parentId} onSaved={(saved) => {
          close();
          refresh();
          if (!area) setFilter({ kind: 'area', areaId: saved.id, includeChildren: true });
          toast.success(area ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'));
        }} />
      ),
    });
  };
  const removeArea = useAreaRemoval(() => {
    setFilter(ALL_AREAS);
    refresh();
  });
  const openClassify = () => {
    if (!areas.data || selection.size === 0) return;
    const tree = areas.data;
    const targets = [...selection.values()];
    dialog.open({
      title: t('분류 변경'),
      content: ({ close }: DialogControls) => (
        <ClassifyForm modelId={model.id} tree={tree} targets={targets} initialArea={filter.kind === 'area' ? filter.areaId : null} onDone={(result) => {
          close();
          setSelection(new Map());
          refresh(); // 성공·실패(409) 모두 최신 소속을 다시 읽는다 (자동으로 다시 보내지 않는다)
          if (result) toast.success(t('분류를 변경했습니다. (변경 {0}개, 그대로 {1}개)', result.changed, result.unchanged));
        }} />
      ),
    });
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
        <EntityForm modelId={model.id} areas={areas.data?.areas} defaultAreaId={filter.kind === 'area' ? filter.areaId : null} onSaved={(entityId) => {
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
      // 화면이 읽은 관계의 수정 일시를 기준값으로 보낸다 (M8-C1) : 그 사이 바뀌었으면 409(공통 알림) — 목록을 다시 불러와 최신 관계를 보게 한다 (자동 재시도 없음)
      try {
        await api(`/api/logical/relationship/${relationship.id}/approval/${!approved}?modifiedAt=${encodeURIComponent(relationship.modifiedAt ?? '')}`, { method: 'PATCH' });
      } catch (error) {
        if (error instanceof ApiError && error.code === 'MODEL_CONFLICT') refresh();
        return;
      }
      refresh();
    });
  };

  const entityHeaders: [string, SortColumn | null][] = [[t('엔터티명'), 'entityName'], [t('설명'), 'entityDescription'], [t('주제 영역'), null], [t('속성 수'), null], [t('관계 수'), null], [t('승인 상태'), 'isApproval'], [t('관리'), null]];
  const relationshipHeaders: [string, RelationshipSortColumn | null][] = [[t('관계명'), 'relationshipName'], [t('출발 엔터티'), 'sourceEntityName'], [t('도착 엔터티'), 'targetEntityName'],
    [t('식별 관계'), null], [t('카디널리티'), null], [t('속성 짝'), null], [t('승인 상태'), null], ...(canEdit || approvalAvailable ? [[t('관리'), null] as [string, null]] : [])];
  const entityRows = entities.data?.data ?? [];
  const relationshipRows = relationships.data?.data ?? [];
  const count = (value: number | undefined) => (value == null ? '…' : value);
  const areaLabel = (id: number | null | undefined) => (id == null ? t('미분류') : areaPath(areaById.get(id)) || '…');
  const pageSelected = entityRows.length > 0 && entityRows.every((r) => selection.has(r.id));
  const scopeLabel = filter.kind === 'unclassified' ? t('미분류') : selectedArea ? areaPath(selectedArea) : '';

  return (
    <div className={`subject-layout${panelOpen ? ' is-panel-open' : ''}`}>
      <button type="button" className="btn subject-panel-toggle" aria-expanded={panelOpen} aria-controls="subjectAreaPanel" onClick={() => setPanelOpen((v) => !v)}>
        {t('주제 영역')} : {filter.kind === 'all' ? t('전체') : scopeLabel}
      </button>
      <div id="subjectAreaPanel" className="subject-panel-slot">
        <SubjectAreaPanel tree={areas.data} filter={filter} onFilter={setFilter} canEdit={canEdit && !!areas.data} onCreate={() => openAreaForm(undefined, filter.kind === 'area' && selectedArea && selectedArea.depth < MAX_AREA_DEPTH ? selectedArea.id : null)}
          loading={areas.isPending} error={areas.isError} />
      </div>
      <div className="subject-main">
      {model.modelDescription && <p className="page-desc">{model.modelDescription}</p>}
      {summary.isError && <p role="alert">{t('논리 모델 요약을 불러오지 못했습니다.')}</p>}
      {filter.kind !== 'all' && (
        <div className="subject-header">
          <div className="subject-header-text">
            <nav aria-label={t('주제 영역 경로')} className="subject-breadcrumb">{filter.kind === 'unclassified' ? t('미분류') : selectedArea ? selectedArea.path.join(' / ') : '…'}</nav>
            <p className="gh-muted">{filter.kind === 'unclassified' ? t('어느 주제 영역에도 속하지 않은 엔터티입니다.')
              : selectedArea ? (selectedArea.areaDescription || t('설명 없음')) : ''}</p>
            {selectedArea && (
              <p className="gh-muted subject-header-counts">{t('직접 소속 엔터티 {0}개 · 하위 영역 포함 {1}개 · 하위 영역 {2}개', selectedArea.directEntityCount, selectedArea.totalEntityCount, selectedArea.childCount)}</p>
            )}
          </div>
          <div className="button-container">
            {filter.kind === 'area' && (
              <label className="subject-include">
                <input type="checkbox" checked={filter.includeChildren} onChange={(e) => setFilter({ ...filter, includeChildren: e.target.checked })} /> {t('하위 영역 포함')}
              </label>
            )}
            {canEdit && selectedArea && selectedArea.depth < MAX_AREA_DEPTH && <button type="button" className="btn btn-sm" onClick={() => openAreaForm(undefined, selectedArea.id)}>{t('하위 영역 추가')}</button>}
            {canEdit && selectedArea && <button type="button" className="btn btn-sm" onClick={() => openAreaForm(selectedArea)}>{t('영역 수정')}</button>}
            {canEdit && selectedArea && <button type="button" className="btn btn-sm btn-danger" onClick={() => removeArea(selectedArea)}>{t('영역 삭제')}</button>}
            {canView('logical-erd') && <button type="button" className="btn btn-sm" onClick={() => navigate(erdPath(undefined, filter))}>{t('ERD에서 보기')}</button>}
          </div>
        </div>
      )}
      <ul className="nav nav-tabs">
        <li className="nav-item">
          <a href="#logical-entities" className={`nav-link${tab === 'entities' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('entities'); }}>{t('엔터티 ({0})', count(summary.data?.entityCount))}</a>
        </li>
        <li className="nav-item">
          <a href="#logical-relationships" className={`nav-link${tab === 'relationships' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('relationships'); }}>{t('관계 ({0})', count(summary.data?.relationshipCount))}</a>
        </li>
        <li className="nav-item">
          <a href="#logical-subtypes" className={`nav-link${tab === 'subtypes' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('subtypes'); }}>{t('서브타입 그룹')}</a>
        </li>
      </ul>

      {tab === 'subtypes' && (
        <>
          {filter.kind !== 'all' && <p className="gh-muted subtype-note">{t('서브타입 그룹은 주제 영역 범위와 관계없이 모델 전체를 보여 줍니다.')}</p>}
          <SubtypeGroupTab model={model} onChanged={refresh} openEntity={openEntity} />
        </>
      )}
      {tab !== 'subtypes' && (
      <>

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
          {canEdit && tab === 'entities' && (
            <button type="button" className="btn" disabled={selection.size === 0 || !areas.data} onClick={openClassify}
              title={selection.size === 0 ? t('목록에서 엔터티를 골라주세요.') : undefined}>{selection.size ? t('분류 변경 ({0})', selection.size) : t('분류 변경')}</button>
          )}
          {canEdit && tab === 'entities' && <button type="button" className="grd-add btn btn-primary" onClick={openEntityForm}>{t('엔터티 등록')}</button>}
          {canEdit && tab === 'relationships' && <button type="button" className="grd-add btn btn-primary" onClick={() => openRelationshipForm()}>{t('관계 등록')}</button>}
        </div>
      </div>

      <div className="dt-container">
        {tab === 'entities' ? (
          <>
            <ListStatus size={entityPage.size} onSize={(size) => setEntityPage({ page: 0, size })}
              text={entities.data ? (search || filter.kind !== 'all' ? t('검색 결과 {0}개 (모델 전체 {1}개)', entities.data.recordsTotal, count(summary.data?.entityCount)) : t('엔터티 {0}개', entities.data.recordsTotal)) : ''}
              loading={entities.isFetching} />
            <div className="dt-layout-row dt-layout-table">
              <div className="dt-layout-cell dt-layout-full">
                <table id="logicalEntityGrid" className="table table-striped table-bordered dataTable grid-fixed" style={{ minWidth: ENTITY_GRID_MIN_WIDTH }}>
                  <colgroup>
                    {canEdit && <col style={{ width: 40 }} />}
                    {ENTITY_WIDTHS.map((width, index) => <col key={index} style={{ width }} />)}
                  </colgroup>
                  <thead>
                    <tr>
                      {canEdit && (
                        <th className="no-sort">
                          <input type="checkbox" aria-label={t('이 페이지의 엔터티 모두 선택')} checked={pageSelected} disabled={!entityRows.length}
                            onChange={() => setSelection(pageSelected ? new Map() : new Map(entityRows.map((r) => [r.id, { id: r.id, name: r.entityName, subjectAreaId: r.subjectAreaId ?? null }])))} />
                        </th>
                      )}
                      {entityHeaders.map(([label, column]) => (
                        <SortHeader key={label} label={label} ordering={column && sort.column === column ? (sort.desc ? 'desc' : 'asc') : null} onSort={column ? () => toggleSort(column) : undefined} />
                      ))}
                    </tr>
                  </thead>
                  <tbody className={entities.isPlaceholderData ? 'is-refreshing' : 'is-refresh-ready'} aria-busy={entities.isFetching || undefined}>
                    {entities.isPending && <SkeletonRows columns={entityHeaders.length + (canEdit ? 1 : 0)} label={t('불러오는 중입니다.')} />}
                    {entityRows.map((entity) => (
                      <tr key={entity.id}>
                        {canEdit && (
                          <td><input type="checkbox" className="row-checkbox" aria-label={t('{0} 선택', entity.entityName)} checked={selection.has(entity.id)} onChange={() => toggleRow(entity)} /></td>
                        )}
                        <td><button type="button" className="logical-link" onClick={() => openEntity(entity.id)}>{entity.entityName}</button></td>
                        <td>{entity.entityDescription || '-'}</td>
                        <td className="subject-cell" title={areaLabel(entity.subjectAreaId)}>
                          {entity.subjectAreaId == null ? <span className="gh-muted">{t('미분류')}</span> : areaLabel(entity.subjectAreaId)}
                        </td>
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
                    {entityRows.length === 0 && !entities.isPending && (
                      <tr><td colSpan={entityHeaders.length + (canEdit ? 1 : 0)} className="dt-empty" role={entities.isError ? 'alert' : undefined}>
                        {entities.isError ? t('엔터티 목록을 불러오지 못했습니다.') : search ? t('검색된 데이터가 없습니다.') : filter.kind !== 'all' ? t('이 범위에 엔터티가 없습니다.') : t('등록된 엔터티가 없습니다.')}
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
              text={relationships.data ? (relationshipFiltered || filter.kind !== 'all' ? t('검색 결과 {0}개 (모델 전체 {1}개)', relationships.data.recordsTotal, count(summary.data?.relationshipCount)) : t('관계 {0}개', relationships.data.recordsTotal)) : ''}
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
                  <tbody className={relationships.isPlaceholderData ? 'is-refreshing' : 'is-refresh-ready'} aria-busy={relationships.isFetching || undefined}>
                    {relationships.isPending && <SkeletonRows columns={relationshipHeaders.length} label={t('불러오는 중입니다.')} />}
                    {relationshipRows.map((r) => (
                      <tr key={r.id}>
                        <td>{r.relationshipName || '-'}</td>
                        <td>
                          {r.sourceEntityName} <span className="gh-muted gh-mono">({participation(r.sourceMin, r.sourceMax)})</span>
                          {filter.kind !== 'all' && <small className="subject-end" title={areaLabel(r.sourceSubjectAreaId)}>{areaLabel(r.sourceSubjectAreaId)}</small>}
                        </td>
                        <td>
                          {r.targetEntityName} <span className="gh-muted gh-mono">({participation(r.targetMin, r.targetMax)})</span>
                          {filter.kind !== 'all' && <small className="subject-end" title={areaLabel(r.targetSubjectAreaId)}>{areaLabel(r.targetSubjectAreaId)}</small>}
                        </td>
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
                    {relationshipRows.length === 0 && !relationships.isPending && (
                      <tr><td colSpan={relationshipHeaders.length} className="dt-empty" role={relationships.isError ? 'alert' : undefined}>
                        {relationships.isError ? t('관계 목록을 불러오지 못했습니다.') : relationshipFiltered ? t('검색된 데이터가 없습니다.') : t('등록된 관계가 없습니다.')}
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
      )}
      </div>
    </div>
  );
}

/**
 * 표의 최소 폭(px) — 공용 그리드의 minWidth 와 같은 방식 (후속 3차 보완). 고정 배치 표의 머리글은 줄바꿈하지 않아(layout.css) 좁은 화면에서 열이 수십 px 로 줄면
 * 머리글이 옆 칸과 겹쳤다. 이 폭 아래에서는 줄이지 않고 표 영역(.dt-layout-table, overflow-x auto)만 가로 스크롤한다 — 문서 전체는 스크롤하지 않는다
 */
const ENTITY_GRID_MIN_WIDTH = 1040;
const RELATIONSHIP_GRID_MIN_WIDTH = 960;
const RELATIONSHIP_GRID_MIN_WIDTH_MANAGED = 1120;

/**
 * 목록 컬럼 폭 (고정) : 엔터티 6열, 관계 7열 + 관리. 머리글은 줄바꿈하므로(logical.css) 한 단어(영어 'Relationships' 등)가 최소 폭에서 들어가는 비율로 잡는다 (후속 3차 보완)
 */
const ENTITY_WIDTHS = ['19%', '22%', '17%', '9%', '9%', '11%', '13%'];
const RELATIONSHIP_WIDTHS = ['16%', '17%', '17%', '12%', '10%', '14%', '14%'];
const RELATIONSHIP_WIDTHS_MANAGED = ['13%', '14%', '14%', '11%', '9%', '11%', '11%', '17%'];


function ModelForm({ model, onSaved }: { model?: LogicalModel; onSaved: (model: LogicalModel) => void }) {
  const dialog = useDialog();
  const toast = useToast();
  const [name, setName] = useState(model?.modelName ?? '');
  const [description, setDescription] = useState(model?.modelDescription ?? '');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('모델명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(model ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      // 이름 중복(프로젝트 안, 대소문자 무시)은 서버에서 검증한다
      const saved = await api<LogicalModel>('/api/logical/model', { method: 'POST', body: { id: model?.id ?? null, modelName: name.trim(), modelDescription: description.trim() || null, modifiedAt: model?.modifiedAt ?? null } });
      onSaved(saved);
      toast.success(model ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'));
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
